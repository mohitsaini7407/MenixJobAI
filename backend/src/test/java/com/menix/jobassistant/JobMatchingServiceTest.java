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
    @Test void freshnessRequiresExactTimeForTwentyFourHourDefault(){JobListing exact=new JobListing();exact.setPostedAt(Instant.parse("2026-10-22T04:00:00Z"));exact.setExactPostingTime(true);JobListing dateOnly=new JobListing();dateOnly.setPublishedAt(LocalDate.parse("2026-10-22"));dateOnly.setPostedAt(Instant.parse("2026-10-22T00:00:00Z"));Instant now=Instant.parse("2026-10-22T05:00:00Z");assertTrue(service.fresh(exact,"24h",now));assertFalse(service.fresh(dateOnly,"24h",now));assertTrue(service.fresh(dateOnly,"3d",now));assertTrue(service.fresh(dateOnly,"all",now));}
    @Test void rejectsFutureAndOldPostingTimestamps(){JobListing j=new JobListing();j.setPostedAt(Instant.parse("2026-10-20T00:00:00Z"));assertFalse(service.fresh(j,"24h",Instant.parse("2026-10-22T00:00:00Z")));j.setPostedAt(Instant.parse("2026-10-23T00:00:00Z"));assertFalse(service.fresh(j,"all-except-future",Instant.parse("2026-10-22T00:00:00Z")));}
    @Test void olderFilterRequiresAnExplicitSelectionAndTreatsUnknownPostingAsUnverified(){
        Instant now=Instant.parse("2026-10-22T12:00:00Z");
        JobListing old=new JobListing();old.setPostedAt(now.minus(Duration.ofDays(8)));old.setExactPostingTime(true);
        JobListing recent=new JobListing();recent.setPostedAt(now.minus(Duration.ofDays(2)));recent.setExactPostingTime(true);
        JobListing unknown=new JobListing();
        assertTrue(service.fresh(old,"older",now));
        assertFalse(service.fresh(recent,"older",now));
        assertFalse(service.fresh(unknown,"older",now));
        assertFalse(service.fresh(unknown,"7d",now));
    }
    @Test void manuallySavedJobsUseDiscoveryTimeForDashboardFreshness(){
        Instant now=Instant.parse("2026-10-22T12:00:00Z");
        JobListing job=new JobListing();job.setImportMethod("MANUAL");job.setFirstSeenAt(now.minus(Duration.ofHours(8)));
        assertTrue(service.fresh(job,"24h",now));
        assertTrue(service.fresh(job,"7d",now));
        assertFalse(service.fresh(job,"older",now));
        job.setFirstSeenAt(now.minus(Duration.ofDays(8)));
        assertFalse(service.fresh(job,"7d",now));
        assertTrue(service.fresh(job,"older",now));
    }
    @Test void pageObservationUsesVerificationTimeWithoutClaimingPostingFreshness(){
        Instant now=Instant.parse("2026-10-22T12:00:00Z");
        JobListing observed=new JobListing();observed.setImportMethod("BROWSER_PAGE");observed.setLastVerifiedAt(now.minus(Duration.ofHours(4)));
        assertTrue(service.fresh(observed,"24h",now));
        assertTrue(service.fresh(observed,"7d",now));
        assertFalse(service.fresh(observed,"older",now));
        observed.setLastVerifiedAt(now.minus(Duration.ofDays(8)));
        assertFalse(service.fresh(observed,"7d",now));
    }
    @Test void visibleSearchResultFreshnessUsesObservationTimeWithoutClaimingPostingDate(){
        Instant now=Instant.parse("2026-10-22T12:00:00Z");
        JobListing imported=new JobListing();imported.setImportMethod("BROWSER_SEARCH_RESULTS");imported.setLastVerifiedAt(now.minus(Duration.ofHours(2)));
        assertTrue(service.fresh(imported,"24h",now));
        assertTrue(service.fresh(imported,"7d",now));
        assertFalse(service.fresh(imported,"older",now));
        imported.setLastVerifiedAt(now.minus(Duration.ofDays(8)));
        assertFalse(service.fresh(imported,"7d",now));
    }
}
