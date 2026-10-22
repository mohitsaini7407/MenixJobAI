package com.menix.jobassistant;

import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Explainable, source-agnostic job scoring. Unknown data is neutral, not an automatic zero. */
@Service
public class JobMatchingService {
    private static final Map<String,String> ALIASES = Map.ofEntries(
        Map.entry("js","javascript"), Map.entry("ts","typescript"), Map.entry("node","node.js"),
        Map.entry("nodejs","node.js"), Map.entry("reactjs","react"), Map.entry("postgres","postgresql"),
        Map.entry("spring","spring boot"), Map.entry("restful api","rest api"), Map.entry("restful apis","rest api"),
        Map.entry("ml","machine learning"), Map.entry("gen ai","generative ai"), Map.entry("genai","generative ai"));
    private static final List<String> COMMON_SKILLS = List.of("java","spring boot","python","javascript","typescript","react","node.js","sql","postgresql","mysql","mongodb","flask","fastapi","rest api","git","docker","aws","machine learning","generative ai","scikit-learn","pandas","numpy","hibernate","microservices","redis","kafka","linux");
    private static final Pattern SENIOR = Pattern.compile("\\b(senior|sr\\.?|principal|staff|lead|director|vp|vice president|head of|manager|chief)\\b|\\b(?:3|4|5|6|7|8|9|10)\\+?\\s+years?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JUNIOR = Pattern.compile("\\b(fresher|entry[- ]level|junior|jr\\.?|graduate|new grad|intern(ship)?|trainee|apprentice|associate|early career)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GLOBAL_LOCATION = Pattern.compile("\\b(worldwide|anywhere|global|any location|any country)\\b");
    private static final Pattern REMOTE_PREFERENCE = Pattern.compile("\\b(remote|worldwide|anywhere|global)\\b");
    private static final Pattern COUNTRY_LIMIT = Pattern.compile("\\b(usa|u\\.s\\.|united states|canada|india|uk|united kingdom|australia|singapore|germany|europe|emea|apac)\\b");
    private static final Set<String> GENERIC_ROLE_WORDS = Set.of("developer","engineer","junior","senior","entry","level","software","graduate","trainee");
    private static final Set<String> NON_GEOGRAPHIC_LOCATIONS = Set.of("remote","worldwide","anywhere","global");
    private static final Map<String,List<String>> ROLE_FAMILIES = Map.of(
        "backend developer",List.of("backend","back end","api developer","server-side","spring boot","fastapi","flask"),
        "java developer",List.of("java","spring boot","jpa","hibernate"),
        "full stack developer",List.of("full stack","fullstack","react","node.js","frontend and backend"),
        "python developer",List.of("python","django","flask","fastapi"),
        "react developer",List.of("react","frontend","front-end","typescript"),
        "ai engineer",List.of("artificial intelligence","machine learning","generative ai","llm","pytorch","tensorflow"));
    private static final Map<String,Pattern> SKILL_PATTERNS = new ConcurrentHashMap<>();

    public Map<String,Object> score(JobListing job, CandidateProfile profile, Instant now) {
        String title=lower(job.getTitle()), description=lower(job.getDescription());
        Set<String> profileSkills=split(profile.getSkills());
        Set<String> required=split(job.getRequiredSkills());
        if (required.isEmpty()) required=COMMON_SKILLS.stream().filter(skill -> containsSkill(description,skill)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> matched=required.stream().filter(r -> profileSkills.stream().anyMatch(s -> related(s,r))).toList();
        List<String> missing=required.stream().filter(r -> !matched.contains(r)).toList();
        int skillScore;
        if (required.isEmpty()) {
            long profileTerms=profileSkills.stream().filter(s -> containsSkill(title+" "+description,s)).count();
            skillScore=profileSkills.isEmpty()?45:(int)Math.round(45+45.0*profileTerms/Math.max(1,Math.min(5,profileSkills.size())));
        } else skillScore=(int)Math.round(100.0*matched.size()/required.size());

        String roleText=title+" "+description.substring(0,Math.min(description.length(),1800));
        Set<String> titles=split(profile.getPreferredTitles());
        int role=titles.stream().mapToInt(t -> roleSimilarity(roleText,t)).max().orElse(45);
        boolean senior=SENIOR.matcher(title+" "+Objects.toString(job.getExperienceLevel(),"")+" "+Objects.toString(job.getExperienceRequirements(),"")).find();
        boolean junior=JUNIOR.matcher(title+" "+Objects.toString(job.getExperienceLevel(),"")+" "+Objects.toString(job.getExperienceRequirements(),"")).find();
        String candidateLevel=lower(profile.getExperienceLevel());
        boolean entryCandidate=candidateLevel.contains("fresh")||candidateLevel.contains("entry")||candidateLevel.contains("junior")||candidateLevel.contains("graduate")||candidateLevel.contains("intern");
        int eligibility=senior&&entryCandidate?0:junior?100:senior?35:65;
        int location=locationScore(profile,job);
        int freshness=50; // Unknown timestamp is neutral for scoring; strict freshness filtering is handled separately.
        if(job.getPostedAt()!=null){long hours=Math.max(0,Duration.between(job.getPostedAt(),now).toHours());freshness=(int)Math.max(0,100-Math.min(100,hours*100/168));}
        int total=(int)Math.round(skillScore*.35+eligibility*.25+role*.20+location*.10+freshness*.10);
        if(senior&&entryCandidate)total=Math.min(total,49);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("score",Math.max(0,Math.min(100,total)));out.put("matchedSkills",matched);out.put("missingSkills",missing);
        out.put("eligibility",senior?"Requires experienced candidate":junior?"Entry-level signals detected":"Experience level unclear");
        out.put("explanation","Skills "+skillScore+"% (35%), experience fit "+eligibility+"% (25%), role relevance "+role+"% (20%), location "+location+"% (10%), freshness "+freshness+"% (10%). Missing timestamps are neutral for scoring and are still excluded by strict recent-posting filters.");
        return out;
    }
    public boolean fresh(JobListing j, String range, Instant now) {
        if(j.getPostedAt()!=null&&j.getPostedAt().isAfter(now))return false;
        if(j.getPublishedAt()!=null&&j.getPublishedAt().isAfter(LocalDate.ofInstant(now,ZoneOffset.UTC)))return false;
        String selected=Objects.toString(range,"24h").toLowerCase(Locale.ROOT);
        if("all".equals(selected)) return true;
        if("older".equals(selected)) {
            if(j.isExactPostingTime()&&j.getPostedAt()!=null)return Duration.between(j.getPostedAt(),now).toHours()>168;
            LocalDate postingDate=j.getPublishedAt()!=null?j.getPublishedAt()
                    :j.getPostedAt()!=null?LocalDate.ofInstant(j.getPostedAt(),ZoneOffset.UTC):null;
            return postingDate!=null&&ChronoUnit.DAYS.between(postingDate,LocalDate.ofInstant(now,ZoneOffset.UTC))>7;
        }
        int hours=switch(selected){case "3d"->72;case "7d"->168;default->24;};
        if(j.isExactPostingTime()&&j.getPostedAt()!=null)return Duration.between(j.getPostedAt(),now).toHours()<=hours;
        if(hours==24||j.getPublishedAt()==null)return false;
        long days=ChronoUnit.DAYS.between(j.getPublishedAt(),LocalDate.ofInstant(now,ZoneOffset.UTC));return days>=0&&days<hours/24;
    }
    private static Set<String> split(String s){if(s==null||s.isBlank())return Set.of();return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[,;|\\n]" )).map(String::trim).filter(x->!x.isBlank()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));}
    private static String canonical(String s){String x=lower(s).replaceAll("[^a-z0-9+#. ]", "").trim();return ALIASES.getOrDefault(x,x);}
    private static boolean related(String a,String b){String x=canonical(a),y=canonical(b);return x.equals(y)||(x.length()>2&&y.length()>2&&(x.contains(y)||y.contains(x)));}
    private static String lower(String s){return Objects.toString(s,"").toLowerCase(Locale.ROOT);}
    private static boolean containsSkill(String text,String skill){String c=canonical(skill);Pattern pattern=SKILL_PATTERNS.computeIfAbsent(c,key->Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(key)+"(?![\\p{L}\\p{N}])",Pattern.CASE_INSENSITIVE));return pattern.matcher(text).find();}
    private static int roleSimilarity(String text,String preferred){
        String p=canonical(preferred);String t=canonical(text);
        if(t.contains(p))return 100;
        int best=0;
        for(var entry:ROLE_FAMILIES.entrySet())if(p.contains(entry.getKey())||entry.getKey().contains(p))for(String term:entry.getValue())if(t.contains(term))best=Math.max(best,75);
        String[] words=p.split("[^a-z0-9+#.]+");int hits=0,denom=0;
        for(String word:words){if(word.length()<3||GENERIC_ROLE_WORDS.contains(word))continue;denom++;if(t.contains(word))hits++;}
        if(denom>0)best=Math.max(best,(int)Math.round(100.0*hits/denom));return best==0?35:best;
    }
    private static int locationScore(CandidateProfile p,JobListing j){
        String prefs=lower(p.getPreferredLocations()),loc=lower(j.getLocation()),mode=lower(j.getWorkMode());if(prefs.isBlank())return 50;
        boolean remote=loc.contains("remote")||mode.contains("remote")||loc.contains("worldwide");
        boolean global=GLOBAL_LOCATION.matcher(loc+" "+mode).find();
        boolean remotePref=REMOTE_PREFERENCE.matcher(prefs).find();
        boolean countryLimited=COUNTRY_LIMIT.matcher(loc).find();
        boolean countryMatch=Arrays.stream(prefs.split("[,;|\\n]")).map(String::trim).filter(x->!x.isBlank()&&!NON_GEOGRAPHIC_LOCATIONS.contains(x)).anyMatch(loc::contains);
        if(global&&remotePref)return 100;if(remote){if(countryLimited)return countryMatch?100:30;return remotePref?90:60;}return countryMatch?100:25;
    }
}
