package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;

@Service
public class JobSourceService {
    private final JobRepository jobs;
    private final JobSourceSyncStateRepository syncStates;
    private final CompanyBoardConfigurationRepository companyBoards;
    private final Map<String,JobSourceConnector> connectors;
    private final EmailAlertImportService emailAlerts;
    private final CareerPageService careerPages;
    @Value("${app.greenhouse-boards:}") private String greenhouseBoards;
    @Value("${app.lever-sites:}") private String leverSites;
    @Value("${app.ashby-board-names:}") private String ashbyBoards;
    @Value("${app.workable-accounts:}") private String workableAccounts;
    @Value("${app.smartrecruiters-companies:}") private String smartRecruitersCompanies;
    @Value("${app.job-verification-max-age-hours:48}") private long verificationMaxAgeHours;
    @Value("${app.public-job-feeds-enabled:false}") private boolean publicJobFeedsEnabled = true;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    public JobSourceService(JobRepository jobs, JobSourceSyncStateRepository syncStates,
                            CompanyBoardConfigurationRepository companyBoards, List<JobSourceConnector> connectors) {
        this(jobs,syncStates,companyBoards,connectors,null,null);
    }
    public JobSourceService(JobRepository jobs, JobSourceSyncStateRepository syncStates,
                            CompanyBoardConfigurationRepository companyBoards, List<JobSourceConnector> connectors,
                            EmailAlertImportService emailAlerts) {
        this(jobs,syncStates,companyBoards,connectors,emailAlerts,null);
    }
    @Autowired
    public JobSourceService(JobRepository jobs, JobSourceSyncStateRepository syncStates,
                            CompanyBoardConfigurationRepository companyBoards, List<JobSourceConnector> connectors,
                            EmailAlertImportService emailAlerts, CareerPageService careerPages) {
        this.jobs = jobs;
        this.syncStates = syncStates;
        this.companyBoards = companyBoards;
        this.emailAlerts = emailAlerts;
        this.careerPages = careerPages;
        this.connectors = connectors.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                connector -> connector.sourceName().toLowerCase(Locale.ROOT), connector -> connector));
    }

    public Map<String, Object> sync() {
        if(!syncing.compareAndSet(false,true)) return Map.of("message","A synchronization is already running","imported",0,"skipped",0,"errors",List.of());
        int imported = 0, updated = 0, rejected = 0, deduplicated = 0, completed = 0;
        List<String> errors = new ArrayList<>();
        boolean emailAlertAttempted=false;
        try {
        if(emailAlerts!=null){
            Map<String,Object> emailResult=emailAlerts.importAlerts();
            imported+=Objects.requireNonNullElse((Integer)emailResult.get("imported"),0);
            emailAlertAttempted=!Boolean.TRUE.equals(emailResult.get("skipped"));
            if(emailResult.containsKey("error"))errors.add("Email alerts: "+emailResult.get("error"));
            else if(emailResult.get("errors") instanceof Integer count&&count>0)errors.add("Email alerts: "+count+" message(s) could not be parsed.");
            else if(emailAlertAttempted)completed++;
        }
        boolean careerPagesAttempted=false;
        if(careerPages!=null&&!careerPages.list().isEmpty()){
            Map<String,Object> careerResult=careerPages.sync();
            imported+=Objects.requireNonNullElse((Integer)careerResult.get("imported"),0);
            updated+=Objects.requireNonNullElse((Integer)careerResult.get("updated"),0);
            rejected+=Objects.requireNonNullElse((Integer)careerResult.get("rejected"),0);
            deduplicated+=Objects.requireNonNullElse((Integer)careerResult.get("deduplicated"),0);
            careerPagesAttempted=!Boolean.TRUE.equals(careerResult.get("skipped"));
            if(careerResult.get("errors") instanceof List<?> careerErrors&&!careerErrors.isEmpty())
                errors.add("Career pages: "+String.join("; ",careerErrors.stream().map(Objects::toString).toList()));
            if(Objects.requireNonNullElse((Integer)careerResult.get("successfulPages"),0)>0)completed++;
        }
        List<String> configuredSources=new ArrayList<>();
        if(publicJobFeedsEnabled) for(String name:List.of("Greenhouse","Lever","Ashby","Workable","SmartRecruiters"))
            if(enabledBoardCount(name)>0)configuredSources.add(name);
        JobSourceConnector adzuna = connectors.get("adzuna");
        if(publicJobFeedsEnabled && adzuna instanceof AdzunaJobSourceConnector connector
                && connector.enabledForSync()) configuredSources.add("Adzuna");
        for(String name:configuredSources) try {
            int[] c=syncFeed(name);
            imported+=c[0];updated+=c[1];rejected+=c[2];deduplicated+=c[3];completed++;
        }catch(Exception e){errors.add(name+": "+errorMessage(e));}
        return Map.of("imported", imported, "updated",updated,"rejected",rejected,"deduplicated",deduplicated,"skipped",deduplicated,
            "sourcesChecked", completed,
            "checkedAt", LocalDateTime.now().toString(), "errors", errors,
            "message",configuredSources.isEmpty()&&!emailAlertAttempted&&!careerPagesAttempted&&!publicJobFeedsEnabled?"Public feeds are disabled. Enable permitted employer-board feeds with PUBLIC_JOB_FEEDS_ENABLED=true."
                    :configuredSources.isEmpty()&&!emailAlertAttempted&&!careerPagesAttempted?"No sources configured. Add sources in Sources."
                    :completed==0&&careerPagesAttempted&&!emailAlertAttempted&&configuredSources.isEmpty()?"Career pages sync failed."
                    :completed==0&&emailAlertAttempted&&configuredSources.isEmpty()?"Email alert sync failed."
                    :completed==0?"All configured job sources failed. You can still add a job URL manually."
                    :careerPagesAttempted&&configuredSources.isEmpty()&&!emailAlertAttempted?"Checked Career pages."
                    :careerPagesAttempted&&emailAlertAttempted&&configuredSources.isEmpty()?"Checked Career pages and email alerts."
                    :emailAlertAttempted&&configuredSources.isEmpty()?"Checked email alerts."
                    :careerPagesAttempted&&emailAlertAttempted?"Checked configured job sources, Career pages, and email alerts."
                    :careerPagesAttempted?"Checked configured job sources and Career pages."
                    :emailAlertAttempted?"Checked configured job sources and email alerts."
                    :"Checked configured job sources.");
        } finally { syncing.set(false); }
    }

    public Map<String,Object> status(){
        List<Map<String,Object>> list=new ArrayList<>();
        List<JobListing> allJobs=jobs.findAll();
        for(String n:List.of("Greenhouse","Lever","Ashby","Workable","SmartRecruiters","Adzuna","LinkedIn","Naukri","Indeed","Google Jobs")){
            int configuredCount=configuredBoardCount(n);
            boolean configured=configuredCount>0;
            boolean employerBoard=Set.of("Greenhouse","Lever","Ashby","Workable","SmartRecruiters").contains(n);
            boolean enabled=employerBoard&&publicJobFeedsEnabled&&enabledBoardCount(n)>0;
            Map<String,Object> row=new LinkedHashMap<>();row.put("source",n);row.put("enabled",enabled);row.put("configured",configured);
            if("Adzuna".equals(n)){
                AdzunaJobSourceConnector adzunaConnector=connectors.get("adzuna") instanceof AdzunaJobSourceConnector value?value:null;
                boolean credentialsConfigured=adzunaConnector!=null&&adzunaConnector.apiConfigured();
                int profileCount=adzunaConnector==null?0:adzunaConnector.profileCount();
                Map<String,Object> usage=adzunaConnector==null?Map.of("callsToday",0,"dailyCallCap",200,"remainingCalls",200):adzunaConnector.usageStatus();
                enabled=publicJobFeedsEnabled&&credentialsConfigured&&profileCount>0;
                row.put("configured",credentialsConfigured);
                row.put("enabled",enabled);
                row.put("apiCredentialsConfigured",credentialsConfigured);
                row.put("profilesConfigured",profileCount);
                row.putAll(usage);
                row.put("status",!credentialsConfigured?"Not configured":profileCount==0?"No search profiles"
                        :!publicJobFeedsEnabled?"Public feeds disabled"
                        :Objects.equals(usage.get("remainingCalls"),0)?"Daily limit reached":"Ready");
            }
            if(employerBoard){row.put("configuredBoards",configuredCount);row.put("enabledBoards",enabledBoardCount(n));}
            row.put("mode",List.of("LinkedIn","Naukri","Indeed","Google Jobs").contains(n)?"Official search link"
                    :"Adzuna".equals(n)?"Job search API":"Employer board API");
            Optional<JobSourceSyncState> state=syncStates.findById(n);
            if(state.isPresent())row.putAll(syncStateMap(state.get()));
            row.put("jobCount",allJobs.stream().filter(j->j.getSource().equalsIgnoreCase(n)).count());
            row.put("verifiedJobs",allJobs.stream().filter(j->j.getSource().equalsIgnoreCase(n)&&"OPEN".equals(j.getAvailabilityStatus())&&recentlyVerified(j,Instant.now())).count());
            row.put("verificationMaxAgeHours",verificationMaxAgeHours);
            list.add(row);
        }
        if(emailAlerts!=null)list.add(emailAlerts.status());
        if(careerPages!=null)list.add(careerPages.status());
        long userSavedJobs=allJobs.stream().filter(job->Set.of("MANUAL","BROWSER_PAGE","BROWSER_SEARCH_RESULTS")
                .contains(Objects.toString(job.getImportMethod(),"").toUpperCase(Locale.ROOT))).count();
        list.add(Map.of("source","Manual","mode","User-saved job listings","configured",userSavedJobs>0,"enabled",false,
                "status","User saved","jobCount",userSavedJobs,"imported",0,"updated",0,"error",""));
        return Map.of("sources",list,"syncing",syncing.get(),"publicFeedsEnabled",publicJobFeedsEnabled,
                "greenhouseBoardsConfigured",configuredBoardCount("Greenhouse"),
                "leverBoardsConfigured",configuredBoardCount("Lever"),
                "ashbyBoardsConfigured",configuredBoardCount("Ashby"),
                "workableAccountsConfigured",configuredBoardCount("Workable"),
                "smartRecruitersCompaniesConfigured",configuredBoardCount("SmartRecruiters"),
                "verificationWindowHours",verificationMaxAgeHours);
    }
    public Map<String,Object> syncSource(String name){
        if("Email alerts".equalsIgnoreCase(name)){
            if(emailAlerts==null)return Map.of("source",name,"imported",0,"error","Email alert importer is unavailable.");
            return emailAlerts.importAlerts();
        }
        if("Career pages".equalsIgnoreCase(name)){
            if(careerPages==null)return Map.of("source",name,"imported",0,"error","Career pages source is unavailable.");
            return careerPages.sync();
        }
        if ("Company boards".equalsIgnoreCase(name)) {
            if (!publicJobFeedsEnabled) return Map.of("source",name,"imported",0,"updated",0,"rejected",0,"deduplicated",0,"error","Public employer-board feeds are disabled.");
            if (!syncing.compareAndSet(false,true)) return Map.of("source",name,"imported",0,"error","A synchronization is already running.");
            try {
                int imported=0,updated=0,rejected=0,deduplicated=0,checked=0;
                List<String> errors=new ArrayList<>();
                for(String provider:List.of("Greenhouse","Lever","Ashby","Workable","SmartRecruiters")){
                    if(enabledBoardCount(provider)==0)continue;
                    try{
                        int[] counts=syncFeed(provider);imported+=counts[0];updated+=counts[1];rejected+=counts[2];deduplicated+=counts[3];checked++;
                    }catch(Exception error){errors.add(provider+": "+errorMessage(error));}
                }
                Map<String,Object> result=new LinkedHashMap<>();result.put("source",name);result.put("imported",imported);
                result.put("updated",updated);result.put("rejected",rejected);result.put("deduplicated",deduplicated);
                result.put("sourcesChecked",checked);result.put("errors",errors);
                if(checked==0)result.put("error","No enabled company boards are configured.");
                return result;
            }finally{syncing.set(false);}
        }
        if(!Set.of("Greenhouse","Lever","Ashby","Workable","SmartRecruiters","Adzuna").contains(name))return Map.of("source",name,"imported",0,"updated",0,"rejected",0,"deduplicated",0,"skipped",0,"error","This source does not support provider sync.");
        if(!publicJobFeedsEnabled)return Map.of("source",name,"imported",0,"updated",0,"rejected",0,"deduplicated",0,"skipped",0,"error","Public employer-board feeds are disabled. Set PUBLIC_JOB_FEEDS_ENABLED=true to enable configured sources.");
        if("Adzuna".equals(name)&&connectors.get("adzuna") instanceof AdzunaJobSourceConnector adzuna&&!adzuna.apiConfigured())
            return Map.of("source",name,"imported",0,"updated",0,"rejected",0,"deduplicated",0,"skipped",0,"error","Adzuna is not configured. Set ADZUNA_APP_ID and ADZUNA_APP_KEY.");
        if(!syncing.compareAndSet(false,true)) return Map.of("source",name,"imported",0,"skipped",0,"error","A synchronization is already running");
        try {
            int[] counts=syncFeed(name);
            return Map.of("source",name,"imported",counts[0],"updated",counts[1],"rejected",counts[2],"deduplicated",counts[3],"skipped",counts[3],"checkedAt",LocalDateTime.now().toString());
        }catch(Exception e){return Map.of("source",name,"imported",0,"updated",0,"rejected",0,"deduplicated",0,"skipped",0,"error",errorMessage(e));}finally{syncing.set(false);}
    }

    private int[] syncFeed(String name) throws Exception{
        JobSourceConnector sourceConnector=connector(name);
        String sourceName=sourceConnector.sourceName();
        JobSourceSyncState state=syncStates.findById(sourceName).orElseGet(()->new JobSourceSyncState(sourceName));
        state.setLastAttemptAt(Instant.now());syncStates.save(state);
        try{
            int[] counts=syncConnector(sourceConnector);
            state.setLastSuccessfulSyncAt(Instant.now());state.setFetched(counts[0]+counts[1]+counts[2]);
            state.setImported(counts[0]);state.setUpdated(counts[1]);state.setRejected(counts[2]);state.setDeduplicated(counts[3]);state.setLatestError("");
            syncStates.save(state);
            return counts;
        }catch(Exception error){
            state.setFetched(0);state.setImported(0);state.setUpdated(0);state.setRejected(0);state.setDeduplicated(0);
            state.setLatestError(errorMessage(error));syncStates.save(state);throw error;
        }
    }
    private int configuredBoardCount(String source){
        String provider=providerKey(source);
        return boardIdentifiers(source,false).size();
    }
    private int enabledBoardCount(String source){
        return boardIdentifiers(source,true).size();
    }
    private Set<String> boardIdentifiers(String source,boolean enabledOnly){
        String provider=providerKey(source);
        if(provider.isBlank())return Set.of();
        Set<String> identifiers=new HashSet<>();
        for(String token:Objects.toString(environmentBoards(source),"").split("[,;\\s]+"))if(!token.isBlank())identifiers.add(token.trim().toLowerCase(Locale.ROOT));
        List<CompanyBoardConfiguration> saved=enabledOnly?companyBoards.findByProviderIgnoreCaseAndEnabledTrue(provider):companyBoards.findByProviderIgnoreCase(provider);
        saved.forEach(board->identifiers.add(board.getIdentifier().toLowerCase(Locale.ROOT)));
        return identifiers;
    }
    private String environmentBoards(String source){
        return switch(source){case "Greenhouse"->greenhouseBoards;case "Lever"->leverSites;case "Ashby"->ashbyBoards;case "Workable"->workableAccounts;case "SmartRecruiters"->smartRecruitersCompanies;default->"";};
    }
    private String providerKey(String source){
        return switch(source){case "Greenhouse"->"GREENHOUSE";case "Lever"->"LEVER";case "Ashby"->"ASHBY";case "Workable"->"WORKABLE";case "SmartRecruiters"->"SMARTRECRUITERS";default->"";};
    }
    private JobSourceConnector connector(String name){
        JobSourceConnector connector=connectors.get(name.toLowerCase(Locale.ROOT));
        if(connector==null)throw new IllegalArgumentException("No provider connector is registered for "+name+".");
        return connector;
    }
    private int[] syncConnector(JobSourceConnector connector) throws Exception{
        List<JsonNode> rows=connector.fetch();
        if(rows==null)throw new IllegalStateException(connector.sourceName()+" returned no response.");
        Map<String,JobListing> knownByUrl=new HashMap<>();
        Map<String,JobListing> knownByExternalId=new HashMap<>();
        Map<String,JobListing> knownByAdzunaKey=new HashMap<>();
        for(JobListing known:jobs.findAll()){
            String knownUrl=canonicalUrl(known.getUrl());
            if(!knownUrl.isBlank())knownByUrl.putIfAbsent(knownUrl,known);
            String key=externalKey(known.getSource(),known.getSourceJobId());
            if(!key.isBlank())knownByExternalId.putIfAbsent(key,known);
            String adzunaKey=adzunaDuplicateKey(known.getTitle(),known.getCompany(),cityFromLocation(known.getLocation()));
            if(!adzunaKey.isBlank())knownByAdzunaKey.putIfAbsent(adzunaKey,known);
        }
        int imported=0,updated=0,rejected=0,deduplicated=0;
        for(JsonNode item:rows){
            try{
                if(item==null||!item.isObject()){rejected++;continue;}
                JobListing incoming=connector.normalize(item);
                String canonical=canonicalUrl(incoming.getUrl());
                if(canonical.isBlank()){rejected++;continue;}
                incoming.setUrl(canonical);
                boolean adzunaSource="Adzuna".equalsIgnoreCase(incoming.getSource());
                String apply=adzunaSource?incoming.getApplyUrl():canonicalUrl(incoming.getApplyUrl());
                if(!apply.isBlank())incoming.setApplyUrl(apply);
                JsonNode tags=item.path("tags").isArray()?item.path("tags"):item.path("jobIndustry");
                JobListing existing=knownByExternalId.get(externalKey(incoming.getSource(),incoming.getSourceJobId()));
                JobListing urlMatch=knownByUrl.get(canonical);
                if(urlMatch!=null&&(existing==null||!Objects.equals(urlMatch.getId(),existing.getId())))existing=urlMatch;
                String adzunaCity=adzunaSource?item.path("_menixCity").asText(""):"";
                if(adzunaCity.isBlank())adzunaCity=cityFromLocation(incoming.getLocation());
                String adzunaKey=adzunaSource?adzunaDuplicateKey(incoming.getTitle(),incoming.getCompany(),adzunaCity):"";
                if(existing==null&&!adzunaKey.isBlank())existing=knownByAdzunaKey.get(adzunaKey);
                Instant observedAt=Instant.now();
                LocalDateTime now=LocalDateTime.now();
                Instant posted=connector.exactTimestamp(item);
                incoming.setDescription(stripHtml(incoming.getDescription()));
                incoming.setRequiredSkills(sourceSkills(tags,incoming.getTitle(),incoming.getDescription()));
                if(incoming.getApplyUrl().isBlank())incoming.setApplyUrl(extractApplyUrl(incoming.getDescription(),canonical));
                if(existing!=null){
                    if(!incoming.getSourceJobId().isBlank())existing.setSourceJobId(incoming.getSourceJobId());
                    if(!Objects.equals(existing.getUrl(),canonical))existing.setUrl(canonical);
                    existing.setTitle(nonblank(incoming.getTitle(),existing.getTitle()));
                    existing.setCompany(nonblank(incoming.getCompany(),existing.getCompany()));
                    existing.setLocation(nonblank(incoming.getLocation(),existing.getLocation()));
                    existing.setWorkMode(nonblank(incoming.getWorkMode(),existing.getWorkMode()));
                    existing.setDescription(nonblank(incoming.getDescription(),existing.getDescription()));
                    existing.setRequiredSkills(nonblank(incoming.getRequiredSkills(),existing.getRequiredSkills()));
                    existing.setApplyUrl(nonblank(incoming.getApplyUrl(),existing.getApplyUrl()));
                    existing.setAttributionUrl(nonblank(incoming.getAttributionUrl(),existing.getAttributionUrl()));
                    if(posted!=null){existing.setPostedAt(posted);existing.setPublishedAt(posted.atZone(ZoneOffset.UTC).toLocalDate());existing.setExactPostingTime(true);}
                    else if(incoming.getPostedAt()!=null&&incoming.getPublishedAt()!=null&&existing.getPostedAt()==null){existing.setPostedAt(incoming.getPostedAt());existing.setPublishedAt(incoming.getPublishedAt());existing.setExactPostingTime(incoming.isExactPostingTime());}
                    existing.setLastSeenAt(observedAt);existing.setLastVerifiedAt(observedAt);existing.setLastSuccessfulSyncAt(observedAt);existing.setAvailabilityStatus("OPEN");existing.setClosedAt(null);existing.setLastUpdatedAt(now);
                    jobs.save(existing);updated++;deduplicated++;
                    knownByUrl.put(canonical,existing);
                    if(!adzunaKey.isBlank())knownByAdzunaKey.put(adzunaKey,existing);
                    String key=externalKey(existing.getSource(),existing.getSourceJobId());if(!key.isBlank())knownByExternalId.put(key,existing);
                }else{
                    incoming.setImportMethod("API");incoming.setAvailabilityStatus("OPEN");
                    incoming.setFirstSeenAt(observedAt);incoming.setLastSeenAt(observedAt);incoming.setLastVerifiedAt(observedAt);incoming.setLastSuccessfulSyncAt(observedAt);incoming.setRetrievedAt(now);incoming.setLastUpdatedAt(now);
                    if(posted!=null){incoming.setPostedAt(posted);incoming.setPublishedAt(posted.atZone(ZoneOffset.UTC).toLocalDate());incoming.setExactPostingTime(true);}
                    jobs.save(incoming);imported++;
                    knownByUrl.put(canonical,incoming);
                    if(!adzunaKey.isBlank())knownByAdzunaKey.put(adzunaKey,incoming);
                    String key=externalKey(incoming.getSource(),incoming.getSourceJobId());if(!key.isBlank())knownByExternalId.put(key,incoming);
                }
                        }catch(Exception rowError){rejected++;}
                    }
                    return new int[]{imported,updated,rejected,deduplicated};
                }
                private String externalKey(String source,String externalId){
                    return source==null||source.isBlank()||externalId==null||externalId.isBlank()?"":source.toLowerCase(Locale.ROOT)+"\u0000"+externalId;
    }
    private String adzunaDuplicateKey(String title,String company,String city){
        if(title==null||company==null||city==null||title.isBlank()||company.isBlank()||city.isBlank())return "";
        return (title.trim()+"\u0000"+company.trim()+"\u0000"+city.trim()).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+"," ");
    }
    private String cityFromLocation(String location){
        if(location==null||location.isBlank())return "";
        int comma=location.indexOf(',');
        return (comma<0?location:location.substring(0,comma)).trim();
    }
    private boolean recentlyVerified(JobListing job,Instant now){
        return job.getLastVerifiedAt()!=null&&!job.getLastVerifiedAt().isBefore(now.minus(Duration.ofHours(verificationMaxAgeHours)));
    }
    private String canonicalUrl(String raw){
        if(raw==null||raw.isBlank()||raw.length()>2048)return "";
        try{
            URI uri=URI.create(raw.trim()).normalize();
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||host.equalsIgnoreCase("localhost")||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal"))return "";
            int port=uri.getPort();
            return new URI("https",null,host.toLowerCase(Locale.ROOT),port==443?-1:port,uri.getPath().isBlank()?"/":uri.getPath(),uri.getQuery(),null).toASCIIString();
        }catch(Exception error){return "";}
    }
    private String nonblank(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private String errorMessage(Exception error){String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());return message.length()>1900?message.substring(0,1900):message;}
    private Map<String,Object> syncStateMap(JobSourceSyncState state){
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("lastAttempt",state.getLastAttemptAt()==null?"":state.getLastAttemptAt().toString());
        row.put("lastSuccessfulSync",state.getLastSuccessfulSyncAt()==null?"":state.getLastSuccessfulSyncAt().toString());
        row.put("fetched",Objects.requireNonNullElse(state.getFetched(),0));row.put("imported",Objects.requireNonNullElse(state.getImported(),0));
        row.put("updated",Objects.requireNonNullElse(state.getUpdated(),0));row.put("rejected",Objects.requireNonNullElse(state.getRejected(),0));
        row.put("deduplicated",Objects.requireNonNullElse(state.getDeduplicated(),0));row.put("error",state.getLatestError());
        return row;
    }
    private String text(JsonNode n, String key) { JsonNode v=n.path(key); return v.isMissingNode()||v.isNull()?"":v.asText(""); }
    private String stripHtml(String s) {
        String clean = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<[^>]+>", " ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();
        return clean.length() > 7900 ? clean.substring(0, 7900) : clean;
    }
    private String sourceSkills(JsonNode tags, String title, String description) {
        String[] skills={"java","spring boot","sql","postgresql","mysql","mongodb","python","javascript","typescript","react","angular","vue","node.js","express","docker","aws","azure","gcp","kubernetes","rest api","graphql","git","hibernate","jpa","microservices","redis","kafka","linux","jenkins","junit","c++","c#",".net","go","rust"};
        List<String> found = new ArrayList<>();
        if (tags != null && tags.isArray()) for (JsonNode tag : tags) addExactSkill(found, skills, tag.asText());
        if (found.isEmpty()) {
            for (String skill : skills) if (java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}])"+java.util.regex.Pattern.quote(skill)+"(?![\\p{L}\\p{N}])").matcher(title).find()) found.add(skill);
        }
        if (found.isEmpty()) {
            java.util.regex.Matcher section = java.util.regex.Pattern.compile("(?is)(?:requirements|qualifications|what you'll bring|what you will bring|what you bring|must[- ]have)\\s*:?\\s*(.{0,1800}?)(?=\\b(?:nice to have|what we offer|benefits|responsibilities|about us|about the role)\\b|$)").matcher(description);
            if (section.find()) for (String skill : skills) if (java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}])"+java.util.regex.Pattern.quote(skill)+"(?![\\p{L}\\p{N}])").matcher(section.group(1)).find()) found.add(skill);
        }
        return String.join(", ", found);
    }
    private void addExactSkill(List<String> found, String[] skills, String tag) {
        String normalized = tag.trim().toLowerCase().replace("js", "javascript");
        for (String skill : skills) if (normalized.equals(skill) || (skill.equals("node.js") && normalized.equals("node")) || (skill.equals("postgresql") && normalized.equals("postgres")) || (skill.equals("rest api") && normalized.equals("rest"))) { if (!found.contains(skill)) found.add(skill); return; }
    }
    private String extractApplyUrl(String html, String listingUrl) {
        java.util.regex.Matcher links = java.util.regex.Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*['\"](https://[^'\"]+)['\"][^>]*>(.*?)</a>").matcher(html);
        while (links.find()) {
            String label = links.group(2).replaceAll("(?is)<[^>]+>", " ").replaceAll("\\s+", " ").trim().toLowerCase();
            String href = links.group(1).replace("&amp;", "&");
            if (href.startsWith("https://") && !href.equals(listingUrl) && label.matches(".*\\b(apply|application|apply now|apply for this job)\\b.*")) return href;
        }
        java.util.regex.Matcher plain = java.util.regex.Pattern.compile("(?i)\\b(?:apply|application)(?:\\s+(?:here|at|now|link))?\\s*[:?-]?\\s*(https://[^\\s<>]+)").matcher(html);
        if (plain.find()) {
            String href = plain.group(1).replaceAll("[.,;)]+$", "");
            if (!href.equals(listingUrl)) return href;
        }
        return "";
    }
}
