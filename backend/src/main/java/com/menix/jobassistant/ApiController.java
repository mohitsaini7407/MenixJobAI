package com.menix.jobassistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClientException;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class ApiController {
    private final ProfileRepository profiles;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final JobSourceService source;
    private final ResumeParserService resumeParser;
    private final JobMatchingService matching;
    private final GeminiMatchService gemini;
    private final SourceRegistryService sourceRegistry;
    private final OutreachService outreach;
    private final PortalCapabilityRegistry portalCapabilities;
    @Value("${app.upload-dir:./uploads}") private String uploadDir;
    private static final Set<String> STATUSES = Set.of("DISCOVERED","SHORTLISTED","DRAFT","AWAITING_APPROVAL","NEEDS_ATTENTION","APPLYING","SUBMITTED","SUBMISSION_FAILED","INTERVIEW","REJECTED","OFFER","WITHDRAWN","DECLINED");
    private static final List<String> SKILLS = List.of("java","spring boot","sql","postgresql","python","javascript","typescript","react","node.js","docker","aws","kubernetes","rest api","git","hibernate","jpa","microservices");

    public ApiController(ProfileRepository profiles, JobRepository jobs, ApplicationRepository applications, JobSourceService source, ResumeParserService resumeParser, JobMatchingService matching, GeminiMatchService gemini, SourceRegistryService sourceRegistry, OutreachService outreach, PortalCapabilityRegistry portalCapabilities) {
        this.profiles=profiles; this.jobs=jobs; this.applications=applications; this.source=source; this.resumeParser=resumeParser; this.matching=matching; this.gemini=gemini; this.sourceRegistry=sourceRegistry; this.outreach=outreach; this.portalCapabilities=portalCapabilities;
    }

    @GetMapping("/health") public Map<String,Object> health(){ return Map.of("status","UP","service","menix-job-assistant","time",LocalDateTime.now().toString()); }

    @GetMapping("/profile") public CandidateProfile getProfile(){ return profiles.findById(1L).orElseGet(()->{CandidateProfile p=new CandidateProfile();p.setExperienceLevel("Fresher / entry level");p.setPreferredTitles("Python Developer, Backend Developer, Java Developer, Java Spring Boot Developer, Full Stack Developer, React Developer, JavaScript Developer, TypeScript Developer, Node.js Developer, AI Engineer, Machine Learning Engineer, Generative AI Developer, Junior Software Engineer, Graduate Software Engineer, Software Development Intern");p.setSkills("Python, Java, JavaScript, TypeScript, React, Node.js, SQL, PostgreSQL, Flask, FastAPI, Spring Boot, REST APIs, Git");p.setPreferredLocations("Remote worldwide, India");return profiles.save(p);}); }
    @PutMapping("/profile") public CandidateProfile saveProfile(@RequestBody CandidateProfile incoming) {
        CandidateProfile p=getProfile();
        p.setFullName(incoming.getFullName()); p.setEmail(incoming.getEmail()); p.setHeadline(incoming.getHeadline());
        p.setPhone(incoming.getPhone()); p.setLinkedinUrl(incoming.getLinkedinUrl()); p.setGithubUrl(incoming.getGithubUrl()); p.setPortfolioUrl(incoming.getPortfolioUrl());
        p.setSkills(incoming.getSkills()); p.setPreferredTitles(incoming.getPreferredTitles()); p.setPreferredLocations(incoming.getPreferredLocations());
        p.setEducation(incoming.getEducation()); p.setMinimumSalary(incoming.getMinimumSalary()); p.setProjects(incoming.getProjects()); p.setExperience(incoming.getExperience());
        p.setExperienceLevel(incoming.getExperienceLevel());
        return profiles.save(p);
    }

    @GetMapping("/jobs") public List<Map<String,Object>> listJobs(@RequestParam(required=false) String q, @RequestParam(defaultValue="24h") String freshness, @RequestParam(defaultValue="80") int minScore, @RequestParam(defaultValue="match") String sort, @RequestParam(required=false) String location, @RequestParam(required=false) String role, @RequestParam(defaultValue="0") int limit) {
        CandidateProfile profile=getProfile();
        Set<Long> inactive=applications.findAll().stream().filter(a->Set.of("DECLINED","SUBMITTED","NEEDS_ATTENTION","APPLYING").contains(a.getStatus())).map(JobApplication::getJobId).collect(Collectors.toSet());
        return jobs.findAll().stream().filter(j->!isRemovedProvider(j)).filter(j->!inactive.contains(j.getId())).filter(j->q==null || (j.getTitle()+" "+j.getCompany()+" "+j.getLocation()+" "+j.getRequiredSkills()).toLowerCase().contains(q.toLowerCase()))
          .filter(j->matching.fresh(j,freshness,java.time.Instant.now()))
          .filter(j->locationMatches(j,location))
          .filter(j->role==null||j.getTitle().toLowerCase().contains(role.toLowerCase()))
          .map(j->{Map<String,Object> m=jobMap(j);Map<String,Object> score=matching.score(j,profile,java.time.Instant.now());m.putAll(score);return m;})
          .filter(j->(int)j.get("score")>=Math.max(0,Math.min(100,minScore)))
          .sorted(sort.equalsIgnoreCase("posted")?Comparator.comparing(m->Objects.toString(m.get("postedAt"),""),Comparator.reverseOrder()):sort.equalsIgnoreCase("discovered")?Comparator.comparing(m->Objects.toString(m.get("retrievedAt"),""),Comparator.reverseOrder()):Comparator.comparingInt((Map<String,Object> m)->(int)m.get("score")).reversed())
          .limit(limit>0?Math.min(limit,500):Long.MAX_VALUE)
          .collect(Collectors.toList());
    }
    @GetMapping("/stats") public Map<String,Object> dashboardStats(@RequestParam(defaultValue="true") boolean includeStrongMatches){List<JobListing> all=jobs.findAll().stream().filter(job->!isRemovedProvider(job)).toList();java.time.Instant now=java.time.Instant.now();long fresh=all.stream().filter(j->matching.fresh(j,"24h",now)).count();Map<String,Object> stats=new HashMap<>();stats.put("totalJobs",all.size());stats.put("newIn24Hours",fresh);if(includeStrongMatches){CandidateProfile profile=getProfile();long strong=all.stream().filter(job->(int)matching.score(job,profile,now).get("score")>=80).count();stats.put("strongMatches",strong);}return stats;}
    @GetMapping("/jobs/{id}") public JobListing getJob(@PathVariable Long id){JobListing j=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));j.setDescription(cleanDescription(j.getDescription()));return j;}
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.CREATED) public JobListing addJob(@RequestBody JobListing input) {
        if(!input.getUrl().isBlank()) {
            input.setUrl(canonicalHttpsUrl(input.getUrl(),"Job URL"));
        }
        if(!input.getApplyUrl().isBlank()) input.setApplyUrl(canonicalHttpsUrl(input.getApplyUrl(),"Application URL"));
        if(!input.getAttributionUrl().isBlank()) input.setAttributionUrl(canonicalHttpsUrl(input.getAttributionUrl(),"Source URL"));
        if(input.getUrl().isBlank()&&!input.getApplyUrl().isBlank()) input.setUrl(input.getApplyUrl());
        if(input.getUrl().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide a secure HTTPS job or careers URL.");
        if(jobs.findByUrl(input.getUrl()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"A job with this URL already exists");
        if(input.getTitle().isBlank()) input.setTitle("Saved external listing");
        if(input.getCompany().isBlank()) input.setCompany(input.getUrl().isBlank()?"Company not specified":hostFromUrl(input.getUrl()));
        if(input.getApplyUrl().isBlank()) input.setApplyUrl(input.getUrl());
        if(input.getAttributionUrl().isBlank()) input.setAttributionUrl(input.getUrl());
        input.setSource(input.getSource().isBlank()?"External Link":input.getSource()); input.setRetrievedAt(LocalDateTime.now());
        return jobs.save(input);
    }
    @PostMapping("/jobs/sync") public Map<String,Object> syncJobs(){
        try { return source.sync(); } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Remote job source could not be reached. You can still use manual job listings.",e);
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Remote job sync failed: "+e.getMessage(),e); }
    }
    @PostMapping("/jobs/sync/{sourceName}") public Map<String,Object> syncOne(@PathVariable String sourceName){return source.syncSource(sourceName);}
    @GetMapping("/sources") public Map<String,Object> sources(){return source.status();}
    @GetMapping("/portal-capabilities") public List<Map<String,Object>> portalCapabilities(){return portalCapabilities.list(source.status());}
    @GetMapping("/sources/manual") public List<JobSourceConnection> manualSources(){return sourceRegistry.list();}
    @PostMapping("/sources/manual") @ResponseStatus(HttpStatus.CREATED) public JobSourceConnection addManualSource(@RequestBody Map<String,Object> body){try{return sourceRegistry.add(body);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}}
    @PutMapping("/sources/manual/{id}") public JobSourceConnection updateManualSource(@PathVariable Long id,@RequestBody Map<String,Object> body){try{return sourceRegistry.update(id,body);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}}
    @DeleteMapping("/sources/manual/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteManualSource(@PathVariable Long id){try{sourceRegistry.delete(id);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}}
    @PostMapping("/sources/manual/{id}/test") public Map<String,Object> testManualSource(@PathVariable Long id){try{return sourceRegistry.test(id);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}}
    @GetMapping("/search-links") public List<Map<String,String>> searchLinks(@RequestParam(defaultValue="software developer") String role,@RequestParam(defaultValue="India") String location){
        String keywords=java.net.URLEncoder.encode(role,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        String place=java.net.URLEncoder.encode(location,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        String q=java.net.URLEncoder.encode(role+" "+location,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        return List.of(
                Map.of("source","Indeed","mode","External Link","url","https://www.indeed.com/jobs?q="+keywords+"&l="+place),
                Map.of("source","Naukri","mode","External Link","url","https://www.naukri.com/"+java.net.URLEncoder.encode(role.replace(' ','-').toLowerCase(Locale.ROOT),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20")+"-jobs-in-"+java.net.URLEncoder.encode(location.replace(' ','-').toLowerCase(Locale.ROOT),java.nio.charset.StandardCharsets.UTF_8).replace("+","%20")),
                Map.of("source","LinkedIn","mode","External Link","url","https://www.linkedin.com/jobs/search/?keywords="+keywords+"&location="+place),
                Map.of("source","Google Jobs","mode","External Link","url","https://www.google.com/search?q="+q+"+jobs&ibp=htl;jobs"),
                Map.of("source","Company careers","mode","External Link","url","https://www.google.com/search?q="+q+"+careers"));
    }
    @GetMapping("/jobs/{id}/match") public Map<String,Object> match(@PathVariable Long id) {
        JobListing j=getJob(id); CandidateProfile p=getProfile();
        return matching.score(j,p,java.time.Instant.now());
    }
    @GetMapping("/ai/status") public Map<String,Object> aiStatus(){return Map.of("enabled",gemini.enabled(),"provider","Google Gemini","model",gemini.model(),"mode","on-demand; profile data is minimized");}
    @PostMapping("/jobs/{id}/ai-match") public Map<String,Object> aiMatch(@PathVariable Long id,@RequestBody Map<String,Object> consent) {
        if(!Boolean.TRUE.equals(consent.get("consent")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Explicit consent is required before sending minimized profile and job data to Gemini");
        JobListing j=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));
        CandidateProfile p=getProfile(); Map<String,Object> ai=gemini.analyze(j,p); Map<String,Object> deterministic=matching.score(j,p,java.time.Instant.now());
        int aiScore=(int)ai.get("aiScore"), detScore=(int)deterministic.get("score");
        int score=(int)Math.round(aiScore*.7+detScore*.3);
        String level=Objects.toString(p.getExperienceLevel(),"").toLowerCase(Locale.ROOT);
        if(java.util.regex.Pattern.compile("\\b(fresh|entry|junior|graduate|intern|trainee)\\b").matcher(level).find()&&"MISMATCH".equals(ai.get("seniorityCompatibility")))score=Math.min(score,49);
        if("RESTRICTED".equals(ai.get("geographicEligibility")))score=Math.min(score,39);
        ai.put("deterministicScore",detScore);ai.put("score",Math.max(0,Math.min(100,score)));ai.put("combinedExplanation","Combined 70% Gemini role/skill assessment with 30% deterministic profile score. Geographic restrictions cap the result; unverified work authorization is never assumed.");
        jobs.save(j); return ai;
    }

    @GetMapping("/applications") public List<JobApplication> listApplications(){return applications.findAll().stream().sorted(Comparator.comparing(JobApplication::getUpdatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).toList();}
    @PostMapping("/applications") @ResponseStatus(HttpStatus.CREATED) public synchronized JobApplication addApplication(@RequestBody Map<String,Object> body) {
        Long jobId=asLong(body.get("jobId")); JobListing j=jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));
        if(applications.existsByJobId(jobId)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This job is already in your tracker");
        JobApplication a=new JobApplication(); a.setJobId(jobId); a.setJobTitle(j.getTitle()); a.setCompany(j.getCompany());
        String requestedStatus=Objects.toString(body.getOrDefault("status","SHORTLISTED"));
        if(!STATUSES.contains(requestedStatus)||"SUBMITTED".equals(requestedStatus)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported initial application status. Menix cannot mark an application submitted during creation.");
        a.setStatus(requestedStatus); a.setNotes(body.getOrDefault("notes","").toString()); a.setUpdatedAt(LocalDateTime.now());
        return applications.save(a);
    }

    @GetMapping("/outreach") public List<OutreachRecord> listOutreach(){return outreach.list();}
    @PostMapping("/outreach") @ResponseStatus(HttpStatus.CREATED) public OutreachRecord createOutreach(@RequestBody Map<String,Object> body) {
        try{return outreach.create(body);}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @PutMapping("/outreach/{id}") public OutreachRecord updateOutreach(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        try{return outreach.update(id,body);}catch(NoSuchElementException error){throw new ResponseStatusException(HttpStatus.NOT_FOUND,error.getMessage());}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @PostMapping("/outreach/{id}/confirm-sent") public OutreachRecord confirmOutreachSent(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        try{return outreach.confirmSent(id,Objects.toString(body.get("evidence"),""));}catch(NoSuchElementException error){throw new ResponseStatusException(HttpStatus.NOT_FOUND,error.getMessage());}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @PostMapping("/jobs/{jobId}/apply") public synchronized JobApplication beginApplication(@PathVariable Long jobId) {
        JobListing j=jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));
        JobApplication a=applications.findByJobId(jobId).orElseGet(JobApplication::new);
        if("DECLINED".equals(a.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Restore this declined job before applying");
        if("SUBMITTED".equals(a.getStatus())) return a;
        if(a.getId()==null){a.setJobId(jobId);a.setJobTitle(j.getTitle());a.setCompany(j.getCompany());}
        a.setStatus("NEEDS_ATTENTION");
        a.setNotes("Application started. Complete and submit the employer form yourself, then update the tracker after confirmation. Menix does not have a verified submission integration for this listing.");
        a.setUpdatedAt(LocalDateTime.now());
        return applications.save(a);
    }
    @PostMapping("/jobs/{jobId}/decline") public synchronized JobApplication declineJob(@PathVariable Long jobId) {
        JobListing j=jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));
        JobApplication a=applications.findByJobId(jobId).orElseGet(JobApplication::new);
        if(a.getId()==null){a.setJobId(jobId);a.setJobTitle(j.getTitle());a.setCompany(j.getCompany());}
        a.setStatus("DECLINED");a.setNotes("Declined by the candidate. Restore from declined history to see this job again.");a.setUpdatedAt(LocalDateTime.now());
        return applications.save(a);
    }
    @PostMapping("/jobs/{jobId}/restore") public synchronized JobApplication restoreDeclinedJob(@PathVariable Long jobId) {
        JobApplication a=applications.findByJobId(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job history not found"));
        if(!"DECLINED".equals(a.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT,"This job is not declined");
        a.setStatus("DISCOVERED");a.setNotes("Restored to active job recommendations.");a.setUpdatedAt(LocalDateTime.now());return applications.save(a);
    }
    @PutMapping("/applications/{id}") public JobApplication updateApplication(@PathVariable Long id,@RequestBody Map<String,Object> body) {
        JobApplication a=applications.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found"));
        if(body.containsKey("status")) {
            String status=body.get("status").toString();
            if(!STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported application status");
            if("SUBMITTED".equals(status)&&!"SUBMITTED".equals(a.getStatus())){
                String evidence=Objects.toString(body.get("submissionEvidence"),"").trim();
                if(evidence.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Add confirmation evidence from the employer portal or confirmation email before marking this application submitted.");
                if(evidence.length()>1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Submission evidence must be 1000 characters or fewer.");
                a.setSubmissionEvidence(evidence);
            }
            a.setStatus(status);
        }
        if(body.containsKey("notes")) a.setNotes(body.get("notes").toString());
        if(body.containsKey("appliedAt")) {String d=Objects.toString(body.get("appliedAt"),""); a.setAppliedAt(d.isBlank()?null:LocalDate.parse(d));}
        if("SUBMITTED".equals(a.getStatus()) && a.getAppliedAt()==null) a.setAppliedAt(LocalDate.now());
        a.setUpdatedAt(LocalDateTime.now()); return applications.save(a);
    }

    @PostMapping("/drafts/cover-letter") public Map<String,String> coverLetter(@RequestBody Map<String,Object> body) {
        Long id=asLong(body.get("jobId")); JobListing j=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found"));
        CandidateProfile p=getProfile(); String name=p.getFullName().isBlank()?"[Your name]":p.getFullName();
        String skills=p.getSkills().isBlank()?"the skills listed in my verified profile":p.getSkills();
        String projects=p.getProjects().isBlank()?"my software projects":p.getProjects();
        String text="Dear Hiring Team,\\n\\nI am writing to express my interest in the "+j.getTitle()+" position at "+j.getCompany()+". "+(p.getHeadline().isBlank()?"I am seeking an entry-level opportunity to contribute and grow as a software developer.":p.getHeadline()+".")+"\\n\\nMy current skills include "+skills+". Through "+projects+", I have been developing my practical problem-solving and software development abilities. I would welcome the opportunity to discuss how my verified skills and learning mindset align with your team's needs.\\n\\nThank you for your time and consideration.\\n\\nRegards,\\n"+name+"\\n\\n[Review and personalize this draft before sending. Add only accurate, job-relevant facts.]";
        return Map.of("jobId",String.valueOf(id),"text",text,"type","rule-based draft","notice","This is a template draft, not an LLM-generated or verified final application.");
    }

    @GetMapping("/interview/questions") public List<Map<String,String>> questions(@RequestParam(required=false) Long jobId) {
        List<Map<String,String>> out=new ArrayList<>();
        addQ(out,"Java","Explain the four pillars of object-oriented programming with examples.");
        addQ(out,"Java","What is the difference between an interface and an abstract class?");
        addQ(out,"Spring Boot","How does dependency injection work in Spring?");
        addQ(out,"REST","How would you design and validate a REST endpoint?");
        addQ(out,"SQL","Explain INNER JOIN versus LEFT JOIN. When would you use each?");
        addQ(out,"Database","What is an index, and what trade-offs does it introduce?");
        addQ(out,"Projects","Walk me through a project you built, the architecture, and a difficult problem you solved.");
        if(jobId!=null) {JobListing j=getJob(jobId); addQ(out,"Job-specific", "Which parts of your verified skills and projects are most relevant to the "+j.getTitle()+" role at "+j.getCompany()+"?");}
        return out;
    }
    private void addQ(List<Map<String,String>> l,String category,String q){l.add(Map.of("category",category,"question",q));}

    @PostMapping("/resumes/upload") public Map<String,Object> uploadResume(@RequestParam("file") MultipartFile file) throws Exception {
        if(file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a file first");
        if(file.getSize()>8*1024*1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Maximum resume size is 8 MB");
        String original=Objects.toString(file.getOriginalFilename(),"resume").replaceAll("[^a-zA-Z0-9._-]","_");
        if(original.length()>180) original=original.substring(original.length()-180);
        String lower=original.toLowerCase();
        if(!(lower.endsWith(".pdf")||lower.endsWith(".docx")||lower.endsWith(".doc")||lower.endsWith(".txt"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Allowed formats: PDF, DOCX, DOC, TXT");
        Map<String,String> suggestions;
        try { suggestions=resumeParser.suggestions(file); }
        catch(IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage(),e); }
        catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This resume could not be read. Try a text-based PDF, DOCX, DOC or TXT file.",e); }
        Path dir=Paths.get(uploadDir).toAbsolutePath().normalize(); Files.createDirectories(dir);
        String saved=UUID.randomUUID()+"_"+original; Path target=dir.resolve(saved).normalize();
        if(!target.startsWith(dir)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filename");
        Files.copy(file.getInputStream(),target,StandardCopyOption.REPLACE_EXISTING);
        return Map.of("stored",true,"filename",original,"storageName",saved,"sizeBytes",file.getSize(),"suggestions",suggestions,
            "message",suggestions.isEmpty()?"Resume saved, but no profile fields could be identified. You can still complete your profile manually.":"Resume saved and parsed locally. Review each suggested field before saving your profile.");
    }

    @GetMapping("/resumes") public List<Map<String,Object>> listResumes() {
        Path directory=Paths.get(uploadDir).toAbsolutePath().normalize();
        if(!Files.isDirectory(directory))return List.of();
        try(var paths=Files.list(directory)){
            return paths.filter(path->Files.isRegularFile(path)&&!Files.isSymbolicLink(path)).map(path->{
                String storageName=path.getFileName().toString();
                if(!storageName.matches("[0-9a-fA-F-]{36}_[A-Za-z0-9._-]{1,180}"))return null;
                try{return Map.<String,Object>of("storageName",storageName,"filename",storageName.substring(37),"sizeBytes",Files.size(path),"updatedAt",Files.getLastModifiedTime(path).toInstant().toString());}
                catch(Exception error){throw new java.io.UncheckedIOException(new java.io.IOException("Could not read saved resume metadata.",error));}
            }).filter(Objects::nonNull).sorted(Comparator.comparing(row->Objects.toString(row.get("updatedAt"),""),Comparator.reverseOrder())).toList();
        }catch(java.io.UncheckedIOException error){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not read saved resume metadata.",error);}
        catch(Exception error){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not list saved resumes.",error);}
    }

    @GetMapping("/resumes/{storageName}") public ResponseEntity<Resource> downloadResume(@PathVariable String storageName) {
        if(storageName==null||!storageName.matches("[0-9a-fA-F-]{36}_[A-Za-z0-9._-]{1,180}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Saved resume identifier is invalid.");
        Path directory=Paths.get(uploadDir).toAbsolutePath().normalize();
        Path file=directory.resolve(storageName).normalize();
        if(!file.startsWith(directory)||Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved resume was not found.");
        String filename=storageName.substring(37);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,org.springframework.http.ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file));
    }

    private Long asLong(Object value){try{return Long.valueOf(Objects.toString(value,""));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid jobId is required");}}
    private String canonicalHttpsUrl(String raw,String label){
        if(raw==null||raw.length()>2048)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,label+" must be a valid public HTTPS URL.");
        try{
            java.net.URI uri=java.net.URI.create(raw.trim()).normalize();
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null
                    ||host.equalsIgnoreCase("localhost")||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal"))
                throw new IllegalArgumentException();
            int port=uri.getPort();
            java.net.URI canonical=new java.net.URI("https",null,host.toLowerCase(Locale.ROOT),port==443?-1:port,
                    uri.getPath().isBlank()?"/":uri.getPath(),uri.getQuery(),null);
            return canonical.toASCIIString();
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,label+" must be a valid public HTTPS URL.");}
    }
    private String hostFromUrl(String raw){
        try{return java.net.URI.create(raw).getHost();}catch(Exception error){return "Company not specified";}
    }
    private boolean locationMatches(JobListing job,String query){
        if(query==null||query.isBlank()) return true;
        String candidate=Objects.toString(job.getLocation(),"").toLowerCase(Locale.ROOT);
        return Arrays.stream(query.split(",")).map(String::trim).filter(term->!term.isBlank())
                .anyMatch(term->candidate.contains(term.toLowerCase(Locale.ROOT)));
    }
    private boolean isRemovedProvider(JobListing job){return Set.of("remoteok","remote ok").contains(Objects.toString(job.getSource(),"").trim().toLowerCase(Locale.ROOT));}
    private boolean sameSkill(String left,String right){return canonicalSkill(left).equals(canonicalSkill(right));}
    private String canonicalSkill(String value){String s=value.toLowerCase(Locale.ROOT).trim();return switch(s){case "js"->"javascript";case "node"->"node.js";case "postgres"->"postgresql";case "restful api","restful apis"->"rest api";case "reactjs"->"react";default->s;};}
    private Set<String> split(String s){if(s==null||s.isBlank())return Set.of();return Arrays.stream(s.toLowerCase().split("[,;|\\n]")).map(String::trim).filter(x->!x.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));}
    private Map<String,Object> jobMap(JobListing j) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",j.getId());m.put("title",j.getTitle());m.put("company",j.getCompany());m.put("location",j.getLocation());m.put("url",j.getUrl());m.put("applyUrl",j.getApplyUrl());m.put("source",j.getSource());m.put("providerBadge",Set.of("Remotive","Arbeitnow","Jobicy","Greenhouse","Lever").contains(j.getSource())?"Direct API":"External Link");m.put("employmentType",j.getEmploymentType());m.put("experienceLevel",j.getExperienceLevel());m.put("experienceRequirements",j.getExperienceRequirements());m.put("workMode",j.getWorkMode());m.put("salary",j.getSalary());m.put("description",cleanDescription(j.getDescription()));m.put("requiredSkills",j.getRequiredSkills());m.put("publishedAt",j.getPublishedAt());m.put("postedAt",j.getPostedAt());m.put("exactPostingTime",j.isExactPostingTime());m.put("attributionUrl",j.getAttributionUrl());m.put("retrievedAt",j.getRetrievedAt());return m;
    }
    private String cleanDescription(String raw){if(raw==null)return "";return raw.replace("\u00c2\u00a0"," ").replaceAll("(?is)please mention the word.{0,500}?(?:read the job post completely|show you read).{0,120}?(?:\\.|$)"," ").replaceAll("(?i)\\btag\\s+[A-Za-z0-9+/=]{12,}"," ").replaceAll("(?i)#[A-Za-z0-9+/=]{12,}"," ").replaceAll("(?is)this is a beta feature to avoid spam applicants\\.?"," ").replaceAll("\\s+"," ").trim();}
}
