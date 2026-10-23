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
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "null")
public class ApiController {
    private final ProfileRepository profiles;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final JobSourceService source;
    private final CompanyBoardService companyBoardService;
    private final ResumeParserService resumeParser;
    private final JobMatchingService matching;
    private final GeminiMatchService gemini;
    private final SourceRegistryService sourceRegistry;
    private final OutreachService outreach;
    private final PortalCapabilityRegistry portalCapabilities;
    private final AutofillSessionRepository autofillSessions;
    private final SmartApplyAnswerRepository smartApplyAnswers;
    private final SmartApplyPreferencesRepository smartApplyPreferences;
    private final JobSourceSyncStateRepository jobSourceSyncStates;
    private final AdzunaSearchProfileService adzunaSearchProfiles;
    private final EmailAlertSettingsService emailAlertSettings;
    private final EmailAlertImportService emailAlertImporter;
    private final CareerPageService careerPages;
    private final DailySyncService dailySync;
    @Value("${app.upload-dir:./uploads}") private String uploadDir;
    @Value("${app.job-verification-max-age-hours:48}") private long verificationMaxAgeHours;
    @Value("${app.retired-job-sources:Remotive,Arbeitnow,Jobicy,RemoteOK}") private String retiredJobSources;
    private static final Set<String> STATUSES = Set.of("DISCOVERED","SHORTLISTED","DRAFT","AWAITING_APPROVAL","NEEDS_ATTENTION","APPLYING","SUBMITTED","SUBMISSION_FAILED","INTERVIEW","REJECTED","OFFER","WITHDRAWN","DECLINED","NOT_COMPLETED_YET");
    private static final List<String> SKILLS = List.of("java","spring boot","sql","postgresql","python","javascript","typescript","react","node.js","docker","aws","kubernetes","rest api","git","hibernate","jpa","microservices");

    public ApiController(ProfileRepository profiles, JobRepository jobs, ApplicationRepository applications, JobSourceService source, CompanyBoardService companyBoardService, ResumeParserService resumeParser, JobMatchingService matching, GeminiMatchService gemini, SourceRegistryService sourceRegistry, OutreachService outreach, PortalCapabilityRegistry portalCapabilities, AutofillSessionRepository autofillSessions, SmartApplyAnswerRepository smartApplyAnswers, SmartApplyPreferencesRepository smartApplyPreferences, JobSourceSyncStateRepository jobSourceSyncStates, AdzunaSearchProfileService adzunaSearchProfiles, EmailAlertSettingsService emailAlertSettings, EmailAlertImportService emailAlertImporter, CareerPageService careerPages, DailySyncService dailySync) {
        this.profiles=profiles; this.jobs=jobs; this.applications=applications; this.source=source; this.companyBoardService=companyBoardService; this.resumeParser=resumeParser; this.matching=matching; this.gemini=gemini; this.sourceRegistry=sourceRegistry; this.outreach=outreach; this.portalCapabilities=portalCapabilities; this.autofillSessions=autofillSessions; this.smartApplyAnswers=smartApplyAnswers; this.smartApplyPreferences=smartApplyPreferences; this.jobSourceSyncStates=jobSourceSyncStates; this.adzunaSearchProfiles=adzunaSearchProfiles; this.emailAlertSettings=emailAlertSettings; this.emailAlertImporter=emailAlertImporter; this.careerPages=careerPages; this.dailySync=dailySync;
    }

    @GetMapping("/health") public Map<String,Object> health(){ return Map.of("status","UP","service","menix-job-assistant","time",LocalDateTime.now().toString()); }

    @GetMapping("/profile") public CandidateProfile getProfile(){ return profiles.findById(1L).orElseGet(()->{CandidateProfile p=new CandidateProfile();p.setExperienceLevel("Fresher / entry level");p.setPreferredTitles("Python Developer, Backend Developer, Java Developer, Java Spring Boot Developer, Full Stack Developer, React Developer, JavaScript Developer, TypeScript Developer, Node.js Developer, AI Engineer, Machine Learning Engineer, Generative AI Developer, Junior Software Engineer, Graduate Software Engineer, Software Development Intern");p.setSkills("Python, Java, JavaScript, TypeScript, React, Node.js, SQL, PostgreSQL, Flask, FastAPI, Spring Boot, REST APIs, Git");p.setPreferredLocations("Remote worldwide, India");return profiles.save(p);}); }
    @PutMapping("/profile") public CandidateProfile saveProfile(@RequestBody CandidateProfile incoming) {
        CandidateProfile p=getProfile();
        p.setFullName(incoming.getFullName()); p.setEmail(incoming.getEmail()); p.setHeadline(incoming.getHeadline());
        p.setPhone(incoming.getPhone()); p.setCurrentLocation(incoming.getCurrentLocation()); p.setLinkedinUrl(incoming.getLinkedinUrl()); p.setGithubUrl(incoming.getGithubUrl()); p.setPortfolioUrl(incoming.getPortfolioUrl());
        p.setSkills(incoming.getSkills()); p.setPreferredTitles(incoming.getPreferredTitles()); p.setPreferredLocations(incoming.getPreferredLocations());
        p.setEducation(incoming.getEducation()); p.setMinimumSalary(incoming.getMinimumSalary()); p.setDegree(incoming.getDegree()); p.setGraduationYear(incoming.getGraduationYear());
        p.setNoticePeriod(incoming.getNoticePeriod()); p.setWorkAuthorization(incoming.getWorkAuthorization()); p.setSponsorshipAnswer(incoming.getSponsorshipAnswer());
        p.setProjects(incoming.getProjects()); p.setExperience(incoming.getExperience());
        p.setExperienceLevel(incoming.getExperienceLevel());
        return profiles.save(p);
    }

    @GetMapping("/smart-apply/answers") public List<SmartApplyAnswer> listSmartApplyAnswers(){
        return smartApplyAnswers.findAll().stream()
                .sorted(Comparator.comparing(SmartApplyAnswer::getApprovedAt,Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
    @PostMapping("/smart-apply/answers") @ResponseStatus(HttpStatus.CREATED)
    public SmartApplyAnswer approveSmartApplyAnswer(@RequestBody Map<String,Object> body){
        String question=boundedText(body.get("question"),"question",1000,true);
        String answer=boundedText(body.get("answer"),"answer",8000,true);
        String key=questionKey(question);
        Optional<SmartApplyAnswer> existing=smartApplyAnswers.findByQuestionKey(key);
        if(existing.isPresent()){
            if(!existing.get().getAnswer().equals(answer))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A different approved answer already exists for this question. Edit that answer instead.");
            return existing.get();
        }
        SmartApplyAnswer item=new SmartApplyAnswer();
        item.setQuestion(question);item.setQuestionKey(key);item.setAnswer(answer);
        item.setCategory(boundedText(body.getOrDefault("category","General"),"category",80,false));
        item.setSource(boundedText(body.getOrDefault("source","Manual"),"source",32,false));
        item.setApprovedAt(java.time.Instant.now());
        return smartApplyAnswers.save(item);
    }
    @PutMapping("/smart-apply/answers/{id}") public SmartApplyAnswer updateSmartApplyAnswer(@PathVariable Long id,@RequestBody Map<String,Object> body){
        SmartApplyAnswer item=smartApplyAnswers.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved answer not found."));
        String question=boundedText(body.getOrDefault("question",item.getQuestion()),"question",1000,true);
        String answer=boundedText(body.getOrDefault("answer",item.getAnswer()),"answer",8000,true);
        String key=questionKey(question);
        smartApplyAnswers.findByQuestionKey(key).filter(other->!other.getId().equals(id)).ifPresent(other->{
            if(!other.getAnswer().equals(answer))throw new ResponseStatusException(HttpStatus.CONFLICT,"A conflicting approved answer already exists for this question.");
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This question already has a duplicate saved answer.");
        });
        String source=boundedText(body.getOrDefault("source",item.getSource()),"source",32,false);
        if(!item.getAnswer().equals(answer)){
            item.setCorrectionCount(item.getCorrectionCount()+1);
            item.setApprovedAt(java.time.Instant.now());
        }else if(!item.getQuestion().equals(question)||!item.getSource().equals(source)){
            item.setApprovedAt(java.time.Instant.now());
        }
        item.setQuestion(question);item.setQuestionKey(key);item.setAnswer(answer);
        item.setSource(source);
        item.setCategory(boundedText(body.getOrDefault("category",item.getCategory()),"category",80,false));
        item.setExcluded(Boolean.parseBoolean(Objects.toString(body.getOrDefault("excluded",item.isExcluded()),"false")));
        return smartApplyAnswers.save(item);
    }
    @PostMapping("/smart-apply/answers/{id}/used") public SmartApplyAnswer recordSmartApplyAnswerUse(@PathVariable Long id){
        SmartApplyAnswer item=smartApplyAnswers.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved answer not found."));
        item.setUsageCount(item.getUsageCount()+1);item.setLastUsedAt(java.time.Instant.now());
        return smartApplyAnswers.save(item);
    }
    @DeleteMapping("/smart-apply/answers/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSmartApplyAnswer(@PathVariable Long id){
        if(!smartApplyAnswers.existsById(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved answer not found.");
        smartApplyAnswers.deleteById(id);
    }
    @DeleteMapping("/smart-apply/answers") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearSmartApplyAnswers(){smartApplyAnswers.deleteAll();}
    @GetMapping("/smart-apply/preferences") public SmartApplyPreferences getSmartApplyPreferences(){
        return smartApplyPreferences.findById(1L).orElseGet(()->smartApplyPreferences.save(new SmartApplyPreferences()));
    }
    @PutMapping("/smart-apply/preferences") public SmartApplyPreferences saveSmartApplyPreferences(@RequestBody Map<String,Object> body){
        SmartApplyPreferences preferences=getSmartApplyPreferences();
        preferences.setAutomaticReuseApprovedAnswers(Boolean.parseBoolean(Objects.toString(body.getOrDefault("automaticReuseApprovedAnswers",preferences.isAutomaticReuseApprovedAnswers()),"false")));
        preferences.setLearningEnabled(Boolean.parseBoolean(Objects.toString(body.getOrDefault("learningEnabled",preferences.isLearningEnabled()),"true")));
        double high=boundedThreshold(body.getOrDefault("highConfidenceThreshold",preferences.getHighConfidenceThreshold()),"highConfidenceThreshold");
        double medium=boundedThreshold(body.getOrDefault("mediumConfidenceThreshold",preferences.getMediumConfidenceThreshold()),"mediumConfidenceThreshold");
        if(medium>=high)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The medium confidence threshold must be lower than the high threshold.");
        preferences.setHighConfidenceThreshold(high);preferences.setMediumConfidenceThreshold(medium);
        return smartApplyPreferences.save(preferences);
    }
    private String boundedText(Object value,String name,int maxLength,boolean required){
        String text=Objects.toString(value,"").trim();
        if(required&&text.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,name+" is required.");
        if(text.length()>maxLength)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,name+" exceeds "+maxLength+" characters.");
        return text;
    }
    private String questionKey(String question){
        String key=question.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
        if(key.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Question must contain letters or numbers.");
        return key;
    }
    private double boundedThreshold(Object value,String name){
        try{
            double threshold=Double.parseDouble(Objects.toString(value,""));
            if(!Double.isFinite(threshold)||threshold<0.5||threshold>1.0)throw new NumberFormatException();
            return threshold;
        }catch(NumberFormatException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,name+" must be between 0.5 and 1.0.");}
    }

    @GetMapping("/jobs") public List<Map<String,Object>> listJobs(@RequestParam(required=false) String q, @RequestParam(defaultValue="24h") String freshness, @RequestParam(defaultValue="80") int minScore, @RequestParam(defaultValue="match") String sort, @RequestParam(required=false) String location, @RequestParam(required=false) String role, @RequestParam(defaultValue="true") boolean activeOnly, @RequestParam(defaultValue="0") int limit, @RequestParam(required=false) String source) {
        CandidateProfile profile=getProfile();
        java.time.Instant now=java.time.Instant.now();
        Set<Long> inactive=applications.findAll().stream().filter(a->Set.of("DECLINED","SUBMITTED","NEEDS_ATTENTION","APPLYING").contains(a.getStatus())).map(JobApplication::getJobId).collect(Collectors.toSet());
        return jobs.findAll().stream().filter(j->!isRemovedProvider(j)).filter(j->source==null||j.getSource().equalsIgnoreCase(source)).filter(j->!Set.of("CLOSED","EXPIRED","INVALID").contains(Objects.toString(j.getAvailabilityStatus(),"").toUpperCase(Locale.ROOT)))
          .filter(j->!inactive.contains(j.getId())).filter(j->!activeOnly||isRecommendationEligible(j,now)).filter(j->q==null || (j.getTitle()+" "+j.getCompany()+" "+j.getLocation()+" "+j.getWorkMode()+" "+j.getRequiredSkills()).toLowerCase().contains(q.toLowerCase()))
          .filter(j->matching.fresh(j,freshness,now))
          .filter(j->locationMatches(j,location))
          .filter(j->role==null||j.getTitle().toLowerCase().contains(role.toLowerCase()))
          .map(j->{Map<String,Object> m=jobMap(j);Map<String,Object> score=matching.score(j,profile,now);m.putAll(score);return m;})
          .filter(j->(int)j.get("score")>=Math.max(0,Math.min(100,minScore)))
          .sorted(sort.equalsIgnoreCase("posted")?Comparator.comparing(m->Objects.toString(m.get("postedAt"),""),Comparator.reverseOrder()):sort.equalsIgnoreCase("discovered")?Comparator.comparing(m->Objects.toString(m.get("retrievedAt"),""),Comparator.reverseOrder()):Comparator.comparingInt((Map<String,Object> m)->(int)m.get("score")).reversed())
          .limit(limit>0?Math.min(limit,500):Long.MAX_VALUE)
          .collect(Collectors.toList());
    }
    @GetMapping("/stats") public Map<String,Object> dashboardStats(@RequestParam(defaultValue="true") boolean includeStrongMatches,@RequestParam(defaultValue="7d") String freshness,@RequestParam(defaultValue="80") int minScore,@RequestParam(required=false) String source,@RequestParam(required=false) String q,@RequestParam(required=false) String location){
        java.time.Instant now=java.time.Instant.now();
        Set<Long> inactive=applications.findAll().stream().filter(a->Set.of("DECLINED","SUBMITTED","NEEDS_ATTENTION","APPLYING").contains(a.getStatus())).map(JobApplication::getJobId).collect(Collectors.toSet());
        List<JobListing> eligible=jobs.findAll().stream().filter(job->!isRemovedProvider(job)&&(source==null||job.getSource().equalsIgnoreCase(source))&&!inactive.contains(job.getId()))
                .filter(job->!Set.of("CLOSED","EXPIRED","INVALID").contains(Objects.toString(job.getAvailabilityStatus(),"").toUpperCase(Locale.ROOT)))
                .filter(job->isRecommendationEligible(job,now)&&matching.fresh(job,freshness,now))
                .filter(job->q==null||q.isBlank()||(job.getTitle()+" "+job.getCompany()+" "+job.getLocation()+" "+job.getWorkMode()+" "+job.getRequiredSkills()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))
                .filter(job->locationMatches(job,location)).toList();
        Map<String,Object> stats=new HashMap<>();stats.put("totalJobs",eligible.size());
        stats.put("newIn24Hours",eligible.stream()
                .filter(job->job.getPublishedAt()!=null||job.getPostedAt()!=null)
                .filter(job->matching.fresh(job,"24h",now)).count());
        stats.put("freshness",freshness);
        stats.put("verifiedJobs",eligible.stream().filter(job->isCurrentlyVerified(job,now)).count());
        stats.put("emailAlertJobs",eligible.stream().filter(this::isEmailAlertJob).count());
        if(includeStrongMatches){CandidateProfile profile=getProfile();long strong=eligible.stream().filter(job->(int)matching.score(job,profile,now).get("score")>=Math.max(0,Math.min(100,minScore))).count();stats.put("strongMatches",strong);}
        return stats;
    }
    @GetMapping("/jobs/diagnostics") public Map<String,Object> jobDiagnostics(){
        Instant now=Instant.now();
        Instant verificationCutoff=now.minus(java.time.Duration.ofHours(verificationMaxAgeHours));
        Instant discoveryCutoff=now.minus(java.time.Duration.ofDays(7));
        List<JobListing> records=jobs.findAll();
        CandidateProfile profile=profiles.findById(1L).orElseGet(CandidateProfile::new);
        Map<String,Object> sourceStatus=source.status();
        Map<String,Long> sourceCounts=records.stream().collect(Collectors.groupingBy(
                job->Objects.toString(job.getSource(),"Unknown"),TreeMap::new,Collectors.counting()));
        List<Map<String,Object>> jobsPerSource=sourceCounts.entrySet().stream().map(entry->
                Map.<String,Object>of("source",entry.getKey(),"count",entry.getValue())).toList();
        List<Map<String,Object>> configuredSources=new ArrayList<>();
        Object sourceRows=sourceStatus.get("sources");
        if(sourceRows instanceof List<?> rows){
            for(Object value:rows){
                if(value instanceof Map<?,?> sourceRow){
                    Map<String,Object> row=new LinkedHashMap<>();
                    sourceRow.forEach((key,item)->row.put(String.valueOf(key),item));
                    configuredSources.add(row);
                }
            }
        }
        List<JobSourceConnection> manualSources=sourceRegistry.list();
        for(JobSourceConnection manualSource:manualSources){
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("source",manualSource.getName());
            row.put("enabled",manualSource.isEnabled());
            row.put("configured",true);
            row.put("mode","Manual source");
            row.put("connectionStatus",manualSource.getConnectionStatus());
            row.put("jobCount",sourceCounts.getOrDefault(manualSource.getName(),0L));
            configuredSources.add(row);
        }
        Set<String> configuredNames=configuredSources.stream()
                .map(row->Objects.toString(row.get("source"),"")).collect(Collectors.toSet());
        for(Map<String,Object> sourceCount:jobsPerSource){
            String name=Objects.toString(sourceCount.get("source"),"Unknown");
            if(!configuredNames.contains(name)){
                configuredSources.add(Map.of("source",name,"enabled",false,"configured",false,
                        "mode","Stored jobs only","jobCount",sourceCount.get("count")));
            }
        }
        long configuredManualSources=manualSources.size();
        long configuredProviderSources=configuredSources.stream()
                .filter(row->!"Manual source".equals(row.get("mode"))&&Boolean.TRUE.equals(row.get("configured"))).count();
        long retiredJobs=records.stream().filter(this::isRemovedProvider).count();
        return Map.ofEntries(
                Map.entry("totalJobs",records.size()),
                Map.entry("jobsPerSource",jobsPerSource),
                Map.entry("jobsFailingVerificationWindow",records.stream().filter(job->job.getLastVerifiedAt()==null||job.getLastVerifiedAt().isBefore(verificationCutoff)).count()),
                Map.entry("jobsOutsideDiscoveryWindow",records.stream().filter(job->job.getFirstSeenAt()==null||job.getFirstSeenAt().isBefore(discoveryCutoff)).count()),
                Map.entry("jobsWithMatchScoreZero",records.stream().filter(job->((Number)matching.score(job,profile,now).get("score")).intValue()==0).count()),
                Map.entry("jobsExcludedRetiredSource",retiredJobs),
                Map.entry("jobsWithoutVerificationTimestamp",records.stream().filter(job->job.getLastVerifiedAt()==null).count()),
                Map.entry("sources",configuredSources),
                Map.entry("publicFeedsEnabled",sourceStatus.get("publicFeedsEnabled")),
                Map.entry("greenhouseBoardsConfigured",sourceStatus.get("greenhouseBoardsConfigured")),
                Map.entry("leverBoardsConfigured",sourceStatus.get("leverBoardsConfigured")),
                Map.entry("ashbyBoardsConfigured",sourceStatus.get("ashbyBoardsConfigured")),
                Map.entry("workableAccountsConfigured",sourceStatus.get("workableAccountsConfigured")),
                Map.entry("smartRecruitersCompaniesConfigured",sourceStatus.get("smartRecruitersCompaniesConfigured")),
                Map.entry("dashboardFreshnessDefault","24h"),
                Map.entry("verificationWindowHours",verificationMaxAgeHours),
                Map.entry("configuredSourceCount",configuredProviderSources+configuredManualSources),
                Map.entry("sourcesConfigured",configuredProviderSources+configuredManualSources>0));
    }
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
        input.setSource(input.getSource().isBlank()?"External Link":input.getSource()); input.setRetrievedAt(LocalDateTime.now());input.setImportMethod("MANUAL");input.setAvailabilityStatus("UNVERIFIED");
        input.setApplicationUrlVerified(!input.getApplyUrl().isBlank()&&!input.getApplyUrl().equals(input.getUrl()));
        return jobs.save(input);
    }
    @PostMapping("/jobs/save-current-page") public JobListing saveCurrentJobPage(@RequestBody JobListing input) {
        input.setUrl(canonicalHttpsUrl(input.getUrl(),"Job URL"));
        String sourceName=sourceForJobUrl(input.getUrl());
        input.setSource(sourceName);
        if(!input.getApplyUrl().isBlank())input.setApplyUrl(canonicalHttpsUrl(input.getApplyUrl(),"Application URL"));
        if(!input.getAttributionUrl().isBlank())input.setAttributionUrl(canonicalHttpsUrl(input.getAttributionUrl(),"Source URL"));
        if(input.getAttributionUrl().isBlank())input.setAttributionUrl(input.getUrl());
        if(input.getTitle().isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A job title is required to save the current page.");
        if(input.getDescription().length()>4000)input.setDescription(input.getDescription().substring(0,4000));
        if(input.getRequiredSkills().length()>1000)input.setRequiredSkills(input.getRequiredSkills().substring(0,1000));

        java.time.Instant observedAt=java.time.Instant.now();
        LocalDateTime observedLocal=LocalDateTime.now();
        JobListing job=jobs.findByUrl(input.getUrl()).orElseGet(JobListing::new);
        boolean isNew=job.getId()==null;
        if(!input.getSourceJobId().isBlank())job.setSourceJobId(input.getSourceJobId().substring(0,Math.min(120,input.getSourceJobId().length())));
        if(!input.getTitle().isBlank())job.setTitle(input.getTitle());
        if(!input.getCompany().isBlank())job.setCompany(input.getCompany());
        if(!input.getLocation().isBlank())job.setLocation(input.getLocation());
        if(!input.getDescription().isBlank())job.setDescription(input.getDescription());
        if(!input.getRequiredSkills().isBlank())job.setRequiredSkills(input.getRequiredSkills());
        if(!input.getExperienceRequirements().isBlank())job.setExperienceRequirements(input.getExperienceRequirements());
        if(!input.getExperienceLevel().isBlank())job.setExperienceLevel(input.getExperienceLevel());
        if(!input.getWorkMode().isBlank())job.setWorkMode(input.getWorkMode());
        if(!input.getEmploymentType().isBlank())job.setEmploymentType(input.getEmploymentType());
        if(!input.getSalary().isBlank())job.setSalary(input.getSalary());
        if(input.getPublishedAt()!=null)job.setPublishedAt(input.getPublishedAt());
        job.setUrl(input.getUrl());job.setSource(sourceName);job.setAttributionUrl(input.getAttributionUrl());
        if(!job.isApplicationUrlVerified()){job.setApplyUrl("");}
        job.setRetrievedAt(observedLocal);job.setLastUpdatedAt(observedLocal);job.setLastSeenAt(observedAt);
        job.setLastVerifiedAt(observedAt);job.setAvailabilityStatus("OPEN");job.setClosedAt(null);job.setImportMethod("BROWSER_PAGE");
        if(isNew)job.setFirstSeenAt(observedAt);
        return jobs.save(job);
    }
    @PostMapping("/jobs/import/naukri-visible") @Transactional
    public Map<String,Object> importVisibleNaukriJobs(@RequestBody List<JobListing> input){
        if(input==null||input.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"No visible Naukri job cards were provided.");
        if(input.size()>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Import at most 50 visible Naukri job cards at a time.");
        JobSourceSyncState syncState=jobSourceSyncStates.findById("Naukri").orElseGet(()->new JobSourceSyncState("Naukri"));
        syncState.setLastAttemptAt(Instant.now());
        jobSourceSyncStates.save(syncState);
        int imported=0,updated=0,rejected=0;
        List<String> diagnostics=new ArrayList<>();
        List<JobListing> upserted=new ArrayList<>();
        for(int index=0;index<input.size();index++){
            JobListing incoming=input.get(index);
            try{
                if(incoming==null)throw new IllegalArgumentException("The listing is empty.");
                String listingUrl=canonicalNaukriListingUrl(incoming.getUrl());
                if(incoming.getTitle().isBlank())throw new IllegalArgumentException("A title is required.");
                if(incoming.getTitle().length()>200||incoming.getCompany().length()>200
                        ||incoming.getLocation().length()>250||incoming.getDescription().length()>2000
                        ||incoming.getSalary().length()>120||incoming.getRequiredSkills().length()>1000
                        ||incoming.getExperienceRequirements().length()>250)
                    throw new IllegalArgumentException("A listing field exceeded its permitted length.");
                String sourceJobId=incoming.getSourceJobId().trim();
                if(sourceJobId.length()>120||!sourceJobId.isEmpty()&&!sourceJobId.matches("[A-Za-z0-9_-]+"))
                    throw new IllegalArgumentException("The Naukri job identifier is invalid.");
                String urlJobId=java.net.URI.create(listingUrl).getPath().matches("(?i).*job-listings.*-(\\d+)/?")
                        ?java.net.URI.create(listingUrl).getPath().replaceFirst("(?i).*job-listings.*-(\\d+)/?","$1"):"";
                if(sourceJobId.isBlank())sourceJobId=urlJobId;
                else if(!urlJobId.isBlank()&&!sourceJobId.equals(urlJobId))
                    throw new IllegalArgumentException("The supplied job identifier does not match the listing URL.");

                Optional<JobListing> byUrl=jobs.findByUrl(listingUrl);
                Optional<JobListing> byId=sourceJobId.isBlank()?Optional.empty()
                        :jobs.findFirstBySourceIgnoreCaseAndSourceJobIdIgnoreCase("Naukri",sourceJobId);
                if(byUrl.isPresent()&&byId.isPresent()&&!byUrl.get().getId().equals(byId.get().getId()))
                    throw new IllegalArgumentException("The listing URL and job identifier refer to different saved jobs.");
                JobListing job=byId.orElseGet(()->byUrl.orElseGet(JobListing::new));
                boolean isNew=job.getId()==null;
                if(!isNew&&!job.getUrl().equals(listingUrl)){
                    Optional<JobListing> urlOwner=byUrl.filter(existing->!existing.getId().equals(job.getId()));
                    if(urlOwner.isPresent())throw new IllegalArgumentException("The canonical listing URL already belongs to another job.");
                }
                job.setUrl(listingUrl);
                job.setSource("Naukri");
                if(!sourceJobId.isBlank())job.setSourceJobId(sourceJobId);
                if(!incoming.getTitle().isBlank())job.setTitle(incoming.getTitle());
                if(!incoming.getCompany().isBlank())job.setCompany(incoming.getCompany());
                if(!incoming.getLocation().isBlank())job.setLocation(incoming.getLocation());
                if(!incoming.getWorkMode().isBlank())job.setWorkMode(incoming.getWorkMode());
                if(!incoming.getDescription().isBlank())job.setDescription(incoming.getDescription());
                if(!incoming.getSalary().isBlank())job.setSalary(incoming.getSalary());
                if(!incoming.getRequiredSkills().isBlank())job.setRequiredSkills(incoming.getRequiredSkills());
                if(!incoming.getExperienceRequirements().isBlank())job.setExperienceRequirements(incoming.getExperienceRequirements());
                if(!incoming.getExperienceLevel().isBlank())job.setExperienceLevel(incoming.getExperienceLevel());
                if(!incoming.getEmploymentType().isBlank())job.setEmploymentType(incoming.getEmploymentType());
                if(incoming.getPublishedAt()!=null)job.setPublishedAt(incoming.getPublishedAt());
                job.setAttributionUrl(listingUrl);
                if(!job.isApplicationUrlVerified())job.setApplyUrl("");
                Instant observedAt=Instant.now();
                LocalDateTime observedLocal=LocalDateTime.now();
                job.setRetrievedAt(isNew?observedLocal:job.getRetrievedAt());
                job.setFirstSeenAt(isNew?observedAt:job.getFirstSeenAt());
                job.setLastSeenAt(observedAt);job.setLastVerifiedAt(observedAt);job.setLastSuccessfulSyncAt(observedAt);
                job.setLastUpdatedAt(observedLocal);job.setAvailabilityStatus("OPEN");job.setClosedAt(null);
                job.setImportMethod("BROWSER_SEARCH_RESULTS");
                upserted.add(jobs.save(job));
                if(isNew)imported++;else updated++;
            }catch(IllegalArgumentException|ResponseStatusException error){
                rejected++;
                diagnostics.add("Card "+(index+1)+": "+Objects.toString(error.getMessage(),"invalid listing"));
            }
        }
        List<JobListing> distinctJobs=upserted.stream().collect(Collectors.toMap(JobListing::getId,job->job,(first,last)->last,LinkedHashMap::new)).values().stream().toList();
        syncState.setLastSuccessfulSyncAt(Instant.now());syncState.setFetched(input.size());
        syncState.setImported(imported);syncState.setUpdated(updated);syncState.setRejected(rejected);
        syncState.setDeduplicated(updated);
        syncState.setLatestError(String.join("; ",diagnostics).substring(0,Math.min(1900,String.join("; ",diagnostics).length())));
        jobSourceSyncStates.save(syncState);
        return Map.of("source","Naukri","attempted",input.size(),"imported",imported,"updated",updated,
                "rejected",rejected,"jobs",distinctJobs,"diagnostics",diagnostics,
                "checkedAt",Instant.now().toString(),"lastSuccessfulSyncAt",syncState.getLastSuccessfulSyncAt());
    }
    @PutMapping("/jobs/{id}/application-url")
    public JobListing verifyApplicationUrl(@PathVariable Long id,@RequestBody Map<String,Object> body){
        JobListing job=jobs.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found."));
        String applicationUrl=canonicalHttpsUrl(Objects.toString(body.get("url"),""),"Application URL");
        if(sameUrlPath(applicationUrl,job.getUrl()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The original listing URL is not a separately verified application destination.");
        job.setApplyUrl(applicationUrl);job.setApplicationUrlVerified(true);
        job.setLastUpdatedAt(LocalDateTime.now());
        return jobs.save(job);
    }
    @PostMapping("/jobs/reconcile") public Map<String,Object> reconcileJobLifecycle(){
        java.time.Instant cutoff=java.time.Instant.now().minus(java.time.Duration.ofHours(verificationMaxAgeHours));
        List<JobListing> records=jobs.findAll();
        Map<String,Long> canonicalCounts=records.stream().map(job->canonicalForReconciliation(job.getUrl()))
                .filter(url->!url.isBlank()).collect(Collectors.groupingBy(url->url,Collectors.counting()));
        long duplicateGroups=canonicalCounts.values().stream().filter(count->count>1).count();
        long duplicateRecords=canonicalCounts.values().stream().filter(count->count>1).mapToLong(count->count-1).sum();
        int markedUnverified=0,markedStale=0,markedInvalid=0;
        for(JobListing job:records){
            if(canonicalForReconciliation(job.getUrl()).isBlank()){
                if(!"INVALID".equalsIgnoreCase(job.getAvailabilityStatus())){job.setAvailabilityStatus("INVALID");markedInvalid++;}
            }else if(job.getAvailabilityStatus()==null||job.getAvailabilityStatus().isBlank()){
                job.setAvailabilityStatus("UNVERIFIED");markedUnverified++;
            }else if("OPEN".equalsIgnoreCase(job.getAvailabilityStatus())&&job.getLastVerifiedAt()==null){
                job.setAvailabilityStatus("UNVERIFIED");markedUnverified++;
            }else if("OPEN".equalsIgnoreCase(job.getAvailabilityStatus())&&job.getLastVerifiedAt().isBefore(cutoff)){
                job.setAvailabilityStatus("STALE");markedStale++;
            }
        }
        jobs.saveAll(records);
        long savedRecords=applications.findAll().stream().filter(app->"SHORTLISTED".equalsIgnoreCase(app.getStatus())).count();
        return Map.of("recordsExamined",records.size(),"markedUnverified",markedUnverified,"markedStale",markedStale,
                "markedInvalid",markedInvalid,"duplicateGroups",duplicateGroups,"duplicateRecords",duplicateRecords,
                "savedRecordsPreserved",savedRecords,"applicationRecordsPreserved",applications.count(),"deletedRecords",0);
    }
    @PostMapping("/jobs/sync") public Map<String,Object> syncJobs(){
        try { return source.sync(); } catch(RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Remote job source could not be reached. You can still use manual job listings.",e);
        } catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Remote job sync failed: "+e.getMessage(),e); }
    }
    @PostMapping("/jobs/sync/{sourceName}") public Map<String,Object> syncOne(@PathVariable String sourceName){return source.syncSource(sourceName);}
    @PostMapping("/jobs/sync/automatic") public Map<String,Object> syncOnApplicationOpen(){return dailySync.runOnApplicationOpen();}
    @GetMapping("/settings/automatic-sync") public DailySyncSettings automaticSyncSettings(){return dailySync.settings();}
    @PutMapping("/settings/automatic-sync") public DailySyncSettings saveAutomaticSyncSettings(@RequestBody Map<String,Object> body){
        try{return dailySync.update(body);}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage(),error);}
    }
    @GetMapping("/sources") public Map<String,Object> sources(){return source.status();}
    @GetMapping("/settings/adzuna-search-profiles") public List<AdzunaSearchProfile> adzunaSearchProfiles(){return adzunaSearchProfiles.list();}
    @PutMapping("/settings/adzuna-search-profiles")
    public List<AdzunaSearchProfile> saveAdzunaSearchProfiles(@RequestBody Map<String,Object> body){
        Object raw=body.get("profiles");
        if(!(raw instanceof List<?> input))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Profiles must be a list.");
        List<Map<String,Object>> rows=new ArrayList<>();
        for(Object item:input){
            if(!(item instanceof Map<?,?> map))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Each profile must be an object.");
            Map<String,Object> row=new LinkedHashMap<>();
            map.forEach((key,value)->row.put(String.valueOf(key),value));
            rows.add(row);
        }
        try{return adzunaSearchProfiles.replace(rows);}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @GetMapping("/settings/email-alerts") public Map<String,Object> emailAlertSettings(){return emailAlertSettings.publicSettings();}
    @PutMapping("/settings/email-alerts")
    public Map<String,Object> saveEmailAlertSettings(@RequestBody Map<String,Object> body){
        try{emailAlertSettings.update(body);return emailAlertSettings.publicSettings();}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @PostMapping("/settings/email-alerts/test") public Map<String,Object> testEmailAlertConnection(){return emailAlertImporter.testConnection();}
    @GetMapping("/settings/career-pages") public List<Map<String,Object>> careerPageSettings(){return careerPages.list();}
    @PostMapping("/settings/career-pages") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addCareerPage(@RequestBody Map<String,Object> body){
        try{return careerPages.add(Objects.toString(body.get("url"),""));}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage());}
    }
    @DeleteMapping("/settings/career-pages/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCareerPage(@PathVariable long id){
        try{careerPages.remove(id);}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.NOT_FOUND,error.getMessage());}
    }
    @GetMapping("/portal-capabilities") public List<Map<String,Object>> portalCapabilities(){return portalCapabilities.list(source.status());}
    @GetMapping("/autofill/capabilities") public List<Map<String,Object>> autofillCapabilities(){
        List<Map<String,Object>> capabilities=new ArrayList<>();
        capabilities.add(Map.of("adapter","accessible-form","provider","Generic","automation","USER_PERMISSION_REQUIRED",
                "controls",List.of("text","email","tel","url","date","number","select","radio","skills-checkbox"),
                "resumeUpload","NATIVE_FILE_CHOOSER_USER_SELECTS_FILE","multiStep","RESCAN_EACH_STEP",
                "limitations","Only visible accessible controls; custom widgets, sensitive questions, CAPTCHA, and unsupported flows require manual entry."));
        capabilities.add(Map.of("adapter","accessible-form","provider","Google Forms","automation","GENERIC_ACCESSIBLE_ONLY",
                "providerSpecificAdapter",false,"limitations","Forms may use custom or cross-origin controls. Only visible accessible controls can be inspected; confirm the form owner permits assisted interaction."));
        for(String provider:List.of("Greenhouse","Lever","Ashby","Workday","Workable")){
            capabilities.add(Map.of("adapter","accessible-form","provider",provider,"automation","GENERIC_ACCESSIBLE_ONLY",
                    "providerSpecificAdapter",false,"limitations","Host recognized only. Provider-specific controls and terms are not validated; use manual entry when the generic accessible form cannot handle the page."));
        }
        capabilities.add(Map.of("adapter","none","provider","LinkedIn","automation","DISABLED",
                "limitations","Automated application assistance is disabled on this platform."));
        return List.copyOf(capabilities);
    }
    @GetMapping("/autofill/sessions") public List<AutofillSession> listAutofillSessions(){
        return autofillSessions.findAll().stream().sorted(Comparator.comparing(AutofillSession::getUpdatedAt,Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }
    @PostMapping("/autofill/sessions") @ResponseStatus(HttpStatus.CREATED) public AutofillSession startAutofillSession(@RequestBody Map<String,Object> body){
        Long jobId=asLong(body.get("jobId"));
        Long applicationId=asLong(body.get("applicationId"));
        jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found."));
        JobApplication application=applications.findById(applicationId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Application record not found."));
        if(!Objects.equals(application.getJobId(),jobId))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The application record does not match the selected job.");
        AutofillSession session=new AutofillSession();
        session.setJobId(jobId);session.setApplicationId(applicationId);session.setStatus("READY");
        session.setUpdatedAt(LocalDateTime.now());
        return autofillSessions.save(session);
    }
    @PutMapping("/autofill/sessions/{id}") public AutofillSession updateAutofillSession(@PathVariable Long id,@RequestBody Map<String,Object> body){
        AutofillSession session=autofillSessions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Autofill session not found."));
        String status=Objects.toString(body.getOrDefault("status",session.getStatus()),"");
        if(!Set.of("READY","DETECTING_FIELDS","AUTOFILLING","NEEDS_REVIEW","SUBMITTED","FAILED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported autofill session status.");
        if("SUBMITTED".equals(session.getStatus())&&!"SUBMITTED".equals(status))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A confirmed submission session cannot be changed to an unsubmitted state.");
        int detected=boundedCounter(body.getOrDefault("fieldsDetected",session.getFieldsDetected()),"fieldsDetected");
        int filled=boundedCounter(body.getOrDefault("fieldsFilled",session.getFieldsFilled()),"fieldsFilled");
        if(filled>detected)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Filled fields cannot exceed detected fields.");
        String diagnostic=Objects.toString(body.getOrDefault("diagnostic",session.getDiagnostic()),"").trim();
        if(diagnostic.length()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The session diagnostic is too long.");
        if("SUBMITTED".equals(status)){
            JobApplication application=applications.findById(session.getApplicationId()).orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"The application record is unavailable."));
            if(!"SUBMITTED".equals(application.getStatus())||application.getSubmissionEvidence().isBlank())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A session can be marked submitted only after verified application confirmation is stored.");
        }
        session.setStatus(status);session.setFieldsDetected(detected);session.setFieldsFilled(filled);session.setDiagnostic(diagnostic);session.setUpdatedAt(LocalDateTime.now());
        return autofillSessions.save(session);
    }

    private int boundedCounter(Object value,String name){
        try{
            int count=Integer.parseInt(Objects.toString(value,""));
            if(count<0||count>5000)throw new NumberFormatException();
            return count;
        }catch(NumberFormatException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,name+" must be between 0 and 5000.");}
    }
    @GetMapping("/sources/manual") public List<JobSourceConnection> manualSources(){return sourceRegistry.list();}
    @PostMapping("/sources/manual") @ResponseStatus(HttpStatus.CREATED) public JobSourceConnection addManualSource(@RequestBody Map<String,Object> body){try{return sourceRegistry.add(body);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}}
    @PutMapping("/sources/manual/{id}") public JobSourceConnection updateManualSource(@PathVariable Long id,@RequestBody Map<String,Object> body){try{return sourceRegistry.update(id,body);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}}
    @DeleteMapping("/sources/manual/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteManualSource(@PathVariable Long id){try{sourceRegistry.delete(id);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}}
    @PostMapping("/sources/manual/{id}/test") public Map<String,Object> testManualSource(@PathVariable Long id){try{return sourceRegistry.test(id);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}}
    @GetMapping("/sources/company-boards") public List<CompanyBoardConfiguration> companyBoards(){return companyBoardService.list();}
    @PostMapping("/sources/company-boards") @ResponseStatus(HttpStatus.CREATED)
    public CompanyBoardConfiguration addCompanyBoard(@RequestBody Map<String,Object> body){
        try{return companyBoardService.add(body);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
    }
    @PutMapping("/sources/company-boards/{id}")
    public CompanyBoardConfiguration updateCompanyBoard(@PathVariable Long id,@RequestBody Map<String,Object> body){
        try{return companyBoardService.update(id,body);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
    }
    @DeleteMapping("/sources/company-boards/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCompanyBoard(@PathVariable Long id){
        try{companyBoardService.delete(id);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}
    }
    @PostMapping("/sources/company-boards/{id}/test")
    public Map<String,Object> testCompanyBoard(@PathVariable Long id){
        try{return companyBoardService.test(id);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}
    }
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

    @PostMapping("/resumes/application-draft") public List<Map<String,String>> applicationDraft(@RequestBody Map<String,Object> request) {
        if(!Boolean.TRUE.equals(request.get("consent")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Explicit consent is required before sending the selected resume text, relevant profile fields, and job details to Gemini.");
        String storageName=Objects.toString(request.get("storageName"),"");
        if(!storageName.matches("[0-9a-fA-F-]{36}_[A-Za-z0-9._-]{1,180}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Saved resume identifier is invalid.");
        Long jobId=asLong(request.get("jobId"));
        List<Map<String,Object>> formFields=validateApplicationFormFields(request.get("fields"));
        JobListing job=jobs.findById(jobId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Job not found."));
        Path directory=Paths.get(uploadDir).toAbsolutePath().normalize();
        Path file=directory.resolve(storageName).normalize();
        if(!file.startsWith(directory)||Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Saved resume was not found.");
        try {
            String resumeText=resumeParser.extractForDraft(file);
            return gemini.draftApplication(job,getProfile(),resumeText,formFields);
        } catch(IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage(),e);
        } catch(ResponseStatusException e) {
            throw e;
        } catch(Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The saved resume could not be read for drafting.",e);
        }
    }

    private List<Map<String,Object>> validateApplicationFormFields(Object rawFields) {
        if(!(rawFields instanceof List<?> fields)||fields.isEmpty()||fields.size()>80)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide between 1 and 80 supported blank form fields.");
        List<Map<String,Object>> validated=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        int totalChars=0;
        Set<String> supportedTypes=Set.of("text","textarea","email","tel","url","number","date","select","radio");
        java.util.regex.Pattern sensitive=java.util.regex.Pattern.compile("(?i)\\b(work authorization|visa|sponsor(ship)?|salary|compensation|gender|sex|ethnicity|race|disabilit(y|ies)|veteran|citizenship|date of birth|birth ?date|age|social security|ssn|tax id|criminal|conviction|background check|security clearance|consent|agree|terms and conditions|legally authorized|authorized to work|are you|do you|will you|have you ever|how many years)\\b");
        for(Object item:fields){
            if(!(item instanceof Map<?,?> field))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A form field description is invalid.");
            String id=boundedFieldText(field.get("id"),16,"id");
            String type=boundedFieldText(field.get("type"),16,"type");
            String label=boundedFieldText(field.get("label"),500,"label");
            String name=boundedFieldText(field.get("name"),180,"name");
            String context=boundedFieldText(field.get("context"),700,"context");
            String placeholder=boundedFieldText(field.get("placeholder"),250,"placeholder");
            Object rawMaxLength=field.get("maxLength");
            if(rawMaxLength!=null&&!(rawMaxLength instanceof Number))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Form field length constraint is invalid.");
            int maxLength=rawMaxLength instanceof Number number?Math.max(0,Math.min(5000,number.intValue())):5000;
            if(!id.matches("f[0-9]{1,3}")||!ids.add(id)||!supportedTypes.contains(type)||label.isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A form field description is unsupported or duplicated.");
            if(sensitive.matcher(label+" "+name+" "+context+" "+placeholder).find())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sensitive or consent-related fields must be completed manually.");
            Map<String,Object> safeField=new LinkedHashMap<>();
            safeField.put("id",id);safeField.put("type",type);safeField.put("label",label);
            safeField.put("name",name);safeField.put("context",context);safeField.put("placeholder",placeholder);
            safeField.put("required",Boolean.TRUE.equals(field.get("required")));
            safeField.put("maxLength",maxLength==0?5000:maxLength);
            Object rawOptions=field.get("options");
            List<Map<String,String>> options=new ArrayList<>();
            if(rawOptions!=null){
                if(!(rawOptions instanceof List<?> choices)||choices.size()>40||!(type.equals("select")||type.equals("radio")))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Form choices are invalid.");
                for(Object choice:choices){
                    if(!(choice instanceof Map<?,?> optionData))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Form choice is invalid.");
                    String optionValue=boundedFieldText(optionData.get("value"),200,"option value");
                    String optionLabel=boundedFieldText(optionData.get("label"),200,"option label");
                    if(optionValue.isBlank()||optionLabel.isBlank())continue;
                    if(sensitive.matcher(optionLabel).find())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sensitive form choices must be completed manually.");
                    options.add(Map.of("value",optionValue,"label",optionLabel));
                }
            }
            if((type.equals("select")||type.equals("radio"))&&options.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choice fields must include their available options.");
            safeField.put("options",List.copyOf(options));
            totalChars+=id.length()+type.length()+label.length()+name.length()+context.length()+placeholder.length()+options.stream().mapToInt(option->option.get("value").length()+option.get("label").length()).sum();
            if(totalChars>24000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The visible form contains too much text to analyze safely.");
            validated.add(Map.copyOf(safeField));
        }
        return List.copyOf(validated);
    }

    private String boundedFieldText(Object value,int maxLength,String fieldName) {
        if(value==null)return "";
        if(!(value instanceof String text)||text.length()>maxLength)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Form field "+fieldName+" is invalid or too long.");
        return text.trim();
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
        a.setNotes("Application started in Browser Assistant. Review the employer form and use AutoApply only when permitted; submission requires explicit confirmation and employer evidence.");
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
    private String canonicalNaukriListingUrl(String raw){
        String canonical=canonicalHttpsUrl(raw,"Naukri listing URL");
        try{
            java.net.URI uri=java.net.URI.create(canonical);
            String host=Objects.toString(uri.getHost(),"").toLowerCase(Locale.ROOT);
            String path=Objects.toString(uri.getPath(),"").toLowerCase(Locale.ROOT);
            if(!(host.equals("naukri.com")||host.endsWith(".naukri.com"))
                    ||!path.contains("job-listings"))
                throw new IllegalArgumentException();
            return new java.net.URI("https",null,host,-1,uri.getPath(),null,null).toASCIIString();
        }catch(Exception error){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only individual Naukri job listing URLs can be imported.");
        }
    }
    private boolean sameUrlPath(String left,String right){
        try{
            java.net.URI a=java.net.URI.create(left),b=java.net.URI.create(right);
            return Objects.equals(a.getScheme(),b.getScheme())&&Objects.equals(a.getHost(),b.getHost())
                    &&Objects.equals(a.getPath(),b.getPath());
        }catch(Exception error){return false;}
    }
    private String hostFromUrl(String raw){
        try{return java.net.URI.create(raw).getHost();}catch(Exception error){return "Company not specified";}
    }
    private String sourceForJobUrl(String raw){
        String host=Objects.toString(hostFromUrl(raw),"").toLowerCase(Locale.ROOT);
        if(host.equals("naukri.com")||host.endsWith(".naukri.com"))return "Naukri";
        if(host.equals("linkedin.com")||host.endsWith(".linkedin.com"))return "LinkedIn";
        if(host.equals("google.com")||host.endsWith(".google.com"))return "Google Jobs";
        if(host.equals("indeed.com")||host.endsWith(".indeed.com"))return "Indeed";
        if(host.equals("glassdoor.com")||host.endsWith(".glassdoor.com"))return "Glassdoor";
        return "External Link";
    }
    private boolean locationMatches(JobListing job,String query){
        if(query==null||query.isBlank()) return true;
        String candidate=(Objects.toString(job.getLocation(),"")+" "+Objects.toString(job.getWorkMode(),"")).toLowerCase(Locale.ROOT);
        return Arrays.stream(query.split(",")).map(String::trim).filter(term->!term.isBlank())
                .anyMatch(term->candidate.contains(term.toLowerCase(Locale.ROOT)));
    }
    private boolean isRemovedProvider(JobListing job){return retiredJobSources().contains(Objects.toString(job.getSource(),"").trim().toLowerCase(Locale.ROOT));}
    private Set<String> retiredJobSources(){return Arrays.stream(Objects.toString(retiredJobSources,"").split("[,;\\s]+")).map(value->value.trim().toLowerCase(Locale.ROOT)).filter(value->!value.isBlank()).collect(Collectors.toUnmodifiableSet());}
    private boolean sameSkill(String left,String right){return canonicalSkill(left).equals(canonicalSkill(right));}
    private String canonicalSkill(String value){String s=value.toLowerCase(Locale.ROOT).trim();return switch(s){case "js"->"javascript";case "node"->"node.js";case "postgres"->"postgresql";case "restful api","restful apis"->"rest api";case "reactjs"->"react";default->s;};}
    private Set<String> split(String s){if(s==null||s.isBlank())return Set.of();return Arrays.stream(s.toLowerCase().split("[,;|\\n]")).map(String::trim).filter(x->!x.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));}
    private boolean isCurrentlyVerified(JobListing job,java.time.Instant now){
        return "OPEN".equalsIgnoreCase(job.getAvailabilityStatus())&&job.getLastVerifiedAt()!=null
                &&!job.getLastVerifiedAt().isBefore(now.minus(java.time.Duration.ofHours(verificationMaxAgeHours)));
    }
    private boolean isEmailAlertJob(JobListing job){
        return Objects.toString(job.getSource(),"").toLowerCase(Locale.ROOT).matches("(naukri|linkedin|indeed|glassdoor) alert");
    }
    private boolean isRecommendationEligible(JobListing job,Instant now){
        if(isCurrentlyVerified(job,now))return true;
        if("MANUAL".equalsIgnoreCase(job.getImportMethod())&&"UNVERIFIED".equalsIgnoreCase(job.getAvailabilityStatus())
                &&job.getFirstSeenAt()!=null&&!job.getFirstSeenAt().isBefore(now.minus(java.time.Duration.ofDays(7)))
                &&!job.getFirstSeenAt().isAfter(now))return true;
        return isEmailAlertJob(job)&&"UNVERIFIED".equalsIgnoreCase(job.getAvailabilityStatus())
                &&job.getFirstSeenAt()!=null&&!job.getFirstSeenAt().isBefore(now.minus(java.time.Duration.ofDays(7)))
                &&!job.getFirstSeenAt().isAfter(now);
    }
    private String canonicalForReconciliation(String raw){
        try{return canonicalHttpsUrl(raw,"Job URL");}catch(ResponseStatusException invalid){return "";}
    }
    private Map<String,Object> jobMap(JobListing j) {
        boolean emailAlert=isEmailAlertJob(j);
        boolean applyUrlVerified=!emailAlert&&(j.isApplicationUrlVerified()
                ||(!"Naukri".equalsIgnoreCase(j.getSource())&&!j.getApplyUrl().isBlank()&&!j.getApplyUrl().equals(j.getUrl())));
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",j.getId());m.put("title",j.getTitle());m.put("company",j.getCompany());m.put("location",j.getLocation());m.put("url",j.getUrl());m.put("canonicalListingUrl",j.getUrl());m.put("applyUrl",j.getApplyUrl());m.put("applicationUrlVerified",applyUrlVerified);m.put("source",j.getSource());m.put("sourceJobId",j.getSourceJobId());m.put("providerBadge",emailAlert?"Email alert":Set.of("Remotive","Arbeitnow","Jobicy","Greenhouse","Lever","Ashby","Workable","SmartRecruiters","Adzuna").contains(j.getSource())?"Direct API":"External Link");m.put("descriptionStatus",j.getDescriptionStatus());m.put("employmentType",j.getEmploymentType());m.put("experienceLevel",j.getExperienceLevel());m.put("experienceRequirements",j.getExperienceRequirements());m.put("workMode",j.getWorkMode());m.put("salary",j.getSalary());m.put("description",cleanDescription(j.getDescription()));m.put("requiredSkills",j.getRequiredSkills());m.put("publishedAt",j.getPublishedAt());m.put("postedAt",j.getPostedAt());m.put("validThrough",j.getValidThrough());m.put("exactPostingTime",j.isExactPostingTime());m.put("attributionUrl",j.getAttributionUrl());m.put("retrievedAt",j.getRetrievedAt());m.put("firstSeenAt",j.getFirstSeenAt());m.put("lastSeenAt",j.getLastSeenAt());m.put("lastVerifiedAt",j.getLastVerifiedAt());m.put("lastSuccessfulSyncAt",j.getLastSuccessfulSyncAt());m.put("availabilityStatus",j.getAvailabilityStatus());m.put("importMethod",j.getImportMethod());return m;
    }
    private String cleanDescription(String raw){if(raw==null)return "";return raw.replace("\u00c2\u00a0"," ").replaceAll("(?is)please mention the word.{0,500}?(?:read the job post completely|show you read).{0,120}?(?:\\.|$)"," ").replaceAll("(?i)\\btag\\s+[A-Za-z0-9+/=]{12,}"," ").replaceAll("(?i)#[A-Za-z0-9+/=]{12,}"," ").replaceAll("(?is)this is a beta feature to avoid spam applicants\\.?"," ").replaceAll("\\s+"," ").trim();}
}
