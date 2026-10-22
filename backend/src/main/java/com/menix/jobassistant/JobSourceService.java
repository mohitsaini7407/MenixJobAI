package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobSourceService {
    private final JobRepository jobs;
    private final Map<String,JobSourceConnector> connectors;
    @Value("${app.greenhouse-boards:}") private String greenhouseBoards;
    @Value("${app.lever-sites:}") private String leverSites;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final Map<String,Map<String,Object>> health = new ConcurrentHashMap<>();
    private volatile Instant lastJobicySync;
    public JobSourceService(JobRepository jobs, List<JobSourceConnector> connectors) {
        this.jobs = jobs;
        this.connectors = connectors.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                connector -> connector.sourceName().toLowerCase(Locale.ROOT), connector -> connector));
    }

    @Scheduled(cron = "${app.job-sync-cron:0 15 8 * * *}")
    public void scheduledSync() {
        try { sync(); } catch (Exception e) {
            System.err.println("Scheduled remote-job sync failed: " + e.getMessage());
        }
    }

    public Map<String, Object> sync() {
        if(!syncing.compareAndSet(false,true)) return Map.of("message","A synchronization is already running","imported",0,"skipped",0,"errors",List.of());
        int imported = 0, skipped = 0, completed = 0;
        List<String> errors = new ArrayList<>();
        try { int[] counts = syncFeed("Remotive"); imported += counts[0]; skipped += counts[1]; completed++; health.put("Remotive",Map.of("lastSuccessfulSync",LocalDateTime.now().toString(),"fetched",counts[0]+counts[1],"imported",counts[0],"duplicatesSkipped",counts[1],"error","")); }
        catch (Exception e) { errors.add("Remotive: " + e.getMessage()); health.put("Remotive",Map.of("lastAttempt",LocalDateTime.now().toString(),"error",Objects.toString(e.getMessage(),"Source unavailable"))); }
        List<String> independent=new ArrayList<>(List.of("Arbeitnow","Jobicy"));if(!greenhouseBoards.isBlank())independent.add("Greenhouse");if(!leverSites.isBlank())independent.add("Lever");
        for(String name:independent) try {if(name.equals("Jobicy")&&lastJobicySync!=null&&Duration.between(lastJobicySync,Instant.now()).toMinutes()<60)throw new IllegalStateException("Jobicy refresh is limited to once per hour");int[] c=syncFeed(name);if(name.equals("Jobicy"))lastJobicySync=Instant.now();imported+=c[0];skipped+=c[1];completed++;health.put(name,Map.of("lastSuccessfulSync",LocalDateTime.now().toString(),"fetched",c[0]+c[1],"imported",c[0],"duplicatesSkipped",c[1],"error",""));}catch(Exception e){errors.add(name+": "+e.getMessage());health.put(name,Map.of("lastAttempt",LocalDateTime.now().toString(),"error",Objects.toString(e.getMessage(),"Source unavailable")));}
        syncing.set(false);
        return Map.of("imported", imported, "skipped", skipped, "sourcesChecked", completed,
            "checkedAt", LocalDateTime.now().toString(), "errors", errors,
            "message", completed == 0 ? "All job sources failed. You can still add a job URL manually." : "Checked all enabled public feeds and configured employer boards.");
    }

    public Map<String,Object> status(){
        List<Map<String,Object>> list=new ArrayList<>();
        for(String n:List.of("Remotive","Arbeitnow","Jobicy","Greenhouse","Lever","The Muse","LinkedIn","Naukri")){
            Map<String,Object> row=new LinkedHashMap<>();row.put("source",n);row.put("enabled",switch(n){case "Remotive","Arbeitnow","Jobicy"->true;case "Greenhouse"->!greenhouseBoards.isBlank();case "Lever"->!leverSites.isBlank();default->false;});row.put("mode",List.of("LinkedIn","Naukri").contains(n)?"Official search link":List.of("Greenhouse","Lever").contains(n)?"Employer board API":"Public feed");row.putAll(health.getOrDefault(n,Map.of()));list.add(row);
        } return Map.of("sources",list,"syncing",syncing.get());
    }
    public Map<String,Object> syncSource(String name){
        if(!syncing.compareAndSet(false,true)) return Map.of("source",name,"imported",0,"skipped",0,"error","A synchronization is already running");
        try {if(name.equalsIgnoreCase("jobicy")&&lastJobicySync!=null&&Duration.between(lastJobicySync,Instant.now()).toMinutes()<60)throw new IllegalStateException("Jobicy refresh is limited to once per hour");int[] counts=syncFeed(name);if(name.equalsIgnoreCase("jobicy"))lastJobicySync=Instant.now();
            Map<String,Object> s=Map.of("lastSuccessfulSync",LocalDateTime.now().toString(),"fetched",counts[0]+counts[1],"imported",counts[0],"duplicatesSkipped",counts[1],"error","");health.put(name,s);return Map.of("source",name,"imported",counts[0],"skipped",counts[1],"checkedAt",LocalDateTime.now().toString());
        }catch(Exception e){health.put(name,Map.of("lastAttempt",LocalDateTime.now().toString(),"error",Objects.toString(e.getMessage(),"Source unavailable")));return Map.of("source",name,"imported",0,"skipped",0,"error",Objects.toString(e.getMessage(),"Source unavailable"));}finally{syncing.set(false);}
    }
    private int[] syncFeed(String name) throws Exception{return syncConnector(connector(name));}
    private JobSourceConnector connector(String name){
        JobSourceConnector connector=connectors.get(name.toLowerCase(Locale.ROOT));
        if(connector==null)throw new IllegalArgumentException("No provider connector is registered for "+name+".");
        return connector;
    }
    private int[] syncConnector(JobSourceConnector connector) throws Exception{
        int imported=0,skipped=0;
        for(JsonNode item:connector.fetch()){
            JobListing job=connector.normalize(item);
            String url=job.getUrl();
            if(url.isBlank()||!url.startsWith("https://")){skipped++;continue;}
            JsonNode tags=item.path("tags").isArray()?item.path("tags"):item.path("jobIndustry");
            if(jobs.findByUrl(url).isPresent()){
                JobListing existing=jobs.findByUrl(url).orElseThrow();
                existing.setRequiredSkills(sourceSkills(tags,job.getTitle(),stripHtml(job.getDescription())));
                if(job.getApplyUrl().isBlank())job.setApplyUrl(extractApplyUrl(job.getDescription(),url));
                if(!job.getApplyUrl().isBlank())existing.setApplyUrl(job.getApplyUrl());
                jobs.save(existing);skipped++;continue;
            }
            job.setDescription(stripHtml(job.getDescription()));
            job.setRequiredSkills(sourceSkills(tags,job.getTitle(),job.getDescription()));
            if(job.getApplyUrl().isBlank())job.setApplyUrl(extractApplyUrl(job.getDescription(),url));
            Instant posted=connector.exactTimestamp(item);
            if(posted!=null){job.setPostedAt(posted);job.setPublishedAt(posted.atZone(ZoneOffset.UTC).toLocalDate());job.setExactPostingTime(true);}
            job.setRetrievedAt(LocalDateTime.now());job.setLastUpdatedAt(LocalDateTime.now());
            jobs.save(job);imported++;
        }
        return new int[]{imported,skipped};
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
