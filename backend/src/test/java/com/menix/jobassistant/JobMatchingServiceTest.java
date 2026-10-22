package com.menix.jobassistant;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JobMatchingServiceTest {
    private final JobMatchingService service=new JobMatchingService();
    private CandidateProfile profile(){CandidateProfile p=new CandidateProfile();p.setSkills("JavaScript, React, PostgreSQL");p.setPreferredTitles("Junior Software Engineer, React Developer");p.setPreferredLocations("India, Remote");p.setExperienceLevel("Fresher / entry level");return p;}
    @Test void recognizesCommonSkillAliasesAndReturnsBoundedScore(){JobListing j=new JobListing();j.setTitle("Junior React Developer");j.setLocation("Remote - India");j.setRequiredSkills("js, reactjs, postgres");j.setPostedAt(Instant.parse("2026-10-22T04:00:00Z"));Map<String,Object> m=service.score(j,profile(),Instant.parse("2026-10-22T05:00:00Z"));assertTrue((int)m.get("score")>=80);assertTrue((int)m.get("score")<=100);assertEquals(3,((java.util.List<?>)m.get("matchedSkills")).size());}
    @Test void capsSeniorRoleForFresher(){JobListing j=new JobListing();j.setTitle("Senior Software Engineer");j.setExperienceRequirements("Requires 5+ years experience");j.setRequiredSkills("Java");assertTrue((int)service.score(j,profile(),Instant.now()).get("score")<60);}
    @Test void countryRestrictedRemoteJobDoesNotMatchWorldwidePreference(){JobListing us=new JobListing();us.setTitle("React Developer");us.setRequiredSkills("React");us.setLocation("Remote - United States only");us.setPostedAt(Instant.now());JobListing india=new JobListing();india.setTitle("React Developer");india.setRequiredSkills("React");india.setLocation("Remote - India");india.setPostedAt(Instant.now());assertTrue((int)service.score(india,profile(),Instant.now()).get("score")>(int)service.score(us,profile(),Instant.now()).get("score"));}
    @Test void freshnessRequiresExactTimeForTwentyFourHourDefault(){JobListing exact=new JobListing();exact.setPostedAt(Instant.parse("2026-10-22T04:00:00Z"));JobListing dateOnly=new JobListing();dateOnly.setPublishedAt(LocalDate.parse("2026-10-22"));Instant now=Instant.parse("2026-10-22T05:00:00Z");assertTrue(service.fresh(exact,"24h",now));assertFalse(service.fresh(dateOnly,"24h",now));assertTrue(service.fresh(dateOnly,"3d",now));assertTrue(service.fresh(dateOnly,"all",now));}
    @Test void rejectsFutureAndOldPostingTimestamps(){JobListing j=new JobListing();j.setPostedAt(Instant.parse("2026-10-20T00:00:00Z"));assertFalse(service.fresh(j,"24h",Instant.parse("2026-10-22T00:00:00Z")));j.setPostedAt(Instant.parse("2026-10-23T00:00:00Z"));assertFalse(service.fresh(j,"all-except-future",Instant.parse("2026-10-22T00:00:00Z")));}
}
