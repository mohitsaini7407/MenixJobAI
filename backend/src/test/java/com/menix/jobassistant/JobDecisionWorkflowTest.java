package com.menix.jobassistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:menix-workflow;DB_CLOSE_DELAY=-1",
        "app.public-job-feeds-enabled=false",
        "app.greenhouse-boards=acme-one,acme-two",
        "app.lever-sites=lever-one",
        "app.job-verification-max-age-hours=48",
        "app.retired-job-sources=Remotive,Arbeitnow,Jobicy,RemoteOK",
        "app.email-alert-imap-password="
})
@AutoConfigureMockMvc
class JobDecisionWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired JobRepository jobs;
    @Autowired CompanyBoardConfigurationRepository companyBoardConfigurations;
    @Autowired JobSourceSyncStateRepository jobSourceSyncStates;
    @Autowired ApplicationRepository applications;
    @Autowired AutofillSessionRepository autofillSessions;
    @Autowired OutreachRepository outreach;
    @Autowired EmailAlertSettingsRepository emailAlertSettings;
    @Autowired JobSourceConnectionRepository jobSourceConnections;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach void clearData() { autofillSessions.deleteAll(); outreach.deleteAll(); applications.deleteAll(); jobs.deleteAll(); companyBoardConfigurations.deleteAll(); emailAlertSettings.deleteAll(); jobSourceConnections.findAll().stream().filter(row->"CAREER_PAGE_JSONLD".equalsIgnoreCase(row.getSourceType())).forEach(jobSourceConnections::delete); }

    private JobListing createJob() {
        JobListing job = new JobListing();
        job.setTitle("Junior Java Developer"); job.setCompany("Test Company");
        job.setLocation("India"); job.setSource("Test"); job.setUrl("https://jobs.example.test/junior-java");
        job.setApplyUrl(job.getUrl()); job.setRequiredSkills("Java, Spring Boot");
        job.setPostedAt(Instant.now()); job.setPublishedAt(LocalDate.now(ZoneOffset.UTC)); job.setExactPostingTime(true);
        job.setAvailabilityStatus("OPEN");job.setImportMethod("API");job.setLastSeenAt(Instant.now());job.setLastVerifiedAt(Instant.now());
        return jobs.save(job);
    }

    @Test void recentlySavedManualJobsAreVisibleInRecommendations() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Saved Full Stack Role\",\"company\":\"Example Labs\",\"location\":\"Remote\",\"url\":\"https://careers.example.test/jobs/42\",\"source\":\"Manual\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importMethod").value("MANUAL"));

        mvc.perform(get("/api/jobs").param("freshness","24h").param("minScore","0").param("activeOnly","true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Saved Full Stack Role"))
                .andExpect(jsonPath("$[0].source").value("Manual"))
                .andExpect(jsonPath("$[0].lastVerifiedAt").value(nullValue()));
    }

    @Test void companyBoardConfigurationIsPersistedAndRequiresSuccessfulTestBeforeEnable() throws Exception {
        MvcResult added=mvc.perform(post("/api/sources/company-boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"ASHBY\",\"company\":\"Example Labs\",\"identifier\":\"example-labs\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("ASHBY"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.testStatus").value("UNVERIFIED"))
                .andReturn();
        long id=objectMapper.readTree(added.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(get("/api/sources/company-boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].company").value("Example Labs"));
        mvc.perform(put("/api/sources/company-boards/{id}",id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/sources/company-boards/{id}",id))
                .andExpect(status().isNoContent());
    }

    @Test void adzunaSearchProfilesArePersistedAndMissingCredentialsAreReported() throws Exception {
        MvcResult defaultsResult=mvc.perform(get("/api/settings/adzuna-search-profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$",hasSize(3)))
                .andReturn();
        JsonNode defaults=objectMapper.readTree(defaultsResult.getResponse().getContentAsString());
        String edited="{\"profiles\":[{\"what\":\"Java developer\",\"where\":\"Jaipur\"}]}";
        mvc.perform(put("/api/settings/adzuna-search-profiles").contentType(MediaType.APPLICATION_JSON).content(edited))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(1)))
                .andExpect(jsonPath("$[0].what").value("Java developer"))
                .andExpect(jsonPath("$[0].where").value("Jaipur"));
        mvc.perform(get("/api/settings/adzuna-search-profiles"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].what").value("Java developer"));
        mvc.perform(put("/api/settings/adzuna-search-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("profiles",defaults))))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(3)));
        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.source=='Adzuna')].status").value(hasItem("Not configured")));
    }

    @Test void emailAlertSettingsAreOffByDefaultPersistAllowlistAndNeverExposeCredentials() throws Exception {
        mvc.perform(get("/api/settings/email-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.hasAppPassword").value(false))
                .andExpect(jsonPath("$.appPassword").doesNotExist())
                .andExpect(jsonPath("$.encryptedPassword").doesNotExist());

        String body = """
                {
                  "imapHost":"imap.example.test","imapPort":993,"emailAddress":"candidate@example.test",
                  "folderName":"INBOX","enabled":false,"markSeen":false,
                  "allowlist":{
                    "Naukri":["*@naukri.com"],"LinkedIn":["jobalerts-noreply@linkedin.com"],
                    "Indeed":["*@indeed.com"],"Glassdoor":["*@glassdoor.com"]
                  }
                }
                """;
        mvc.perform(put("/api/settings/email-alerts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowlist.Naukri[0]").value("*@naukri.com"))
                .andExpect(jsonPath("$.appPassword").doesNotExist())
                .andExpect(jsonPath("$.encryptedPassword").doesNotExist());
        mvc.perform(get("/api/settings/email-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailAddress").value("candidate@example.test"))
                .andExpect(jsonPath("$.allowlist.LinkedIn[0]").value("jobalerts-noreply@linkedin.com"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.appPassword").doesNotExist());
    }

    @Test void careerPageSettingsArePersistedAsDedicatedSourcesAndRejectUnsafeUrls() throws Exception {
        mvc.perform(get("/api/settings/career-pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(post("/api/settings/career-pages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://careers.example.test/jobs\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/settings/career-pages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://127.0.0.1/jobs\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.source=='Career pages')].status").value(hasItem("Not configured")));
    }

    @Test void recentEmailAlertJobsAreRecommendationEligibleWithoutClaimingVerification() throws Exception {
        JobListing alert = new JobListing();
        alert.setTitle("Email alert full-stack role");
        alert.setCompany("Example employer");
        alert.setSource("Naukri alert");
        alert.setUrl("https://www.naukri.com/job-listings-example-123");
        alert.setApplyUrl(alert.getUrl());
        alert.setAvailabilityStatus("UNVERIFIED");
        alert.setImportMethod("EMAIL_ALERT");
        alert.setDescriptionStatus("summary only");
        alert.setFirstSeenAt(Instant.now());
        alert.setPostedAt(null);
        alert.setLastVerifiedAt(null);
        alert = jobs.save(alert);

        mvc.perform(get("/api/jobs").param("freshness", "7d").param("minScore", "0").param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(alert.getId()))
                .andExpect(jsonPath("$[0].providerBadge").value("Email alert"))
                .andExpect(jsonPath("$[0].availabilityStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$[0].descriptionStatus").value("summary only"))
                .andExpect(jsonPath("$[0].applicationUrlVerified").value(false))
                .andExpect(jsonPath("$[0].lastVerifiedAt").value(nullValue()));

        alert.setFirstSeenAt(Instant.now().minus(java.time.Duration.ofDays(8)));
        jobs.save(alert);
        mvc.perform(get("/api/jobs").param("freshness", "7d").param("minScore", "0").param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Email alert full-stack role"))));
    }

    @Test void applyIntentIsPersistentAndDuplicateClicksAreIdempotent() throws Exception {
        JobListing job = createJob();
        mvc.perform(post("/api/jobs/{id}/apply", job.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_ATTENTION"));
        mvc.perform(post("/api/jobs/{id}/apply", job.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_ATTENTION"));
        mvc.perform(get("/api/applications")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(job.getTitle()))));
    }

    @Test void declinePersistsHidesRecommendationAndCanBeRestored() throws Exception {
        JobListing job = createJob();
        mvc.perform(post("/api/jobs/{id}/decline", job.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(job.getTitle()))));
        mvc.perform(get("/api/applications")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/api/jobs/{id}/restore", job.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCOVERED"));
        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(job.getTitle())));
    }

    @Test void commaSeparatedLocationSearchMatchesAnyLocationTerm() throws Exception {
        JobListing job = createJob();
        job.setLocation("Remote worldwide");
        jobs.save(job);

        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0")
                        .param("location", "Remote, India"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(job.getTitle())));
    }

    @Test void savingNaukriJobPageMakesItDiscoverableAndRefreshesDuplicateObservation() throws Exception {
        String requestBody = """
                {"title":"Full Stack Developer","company":"Naukri Example","location":"India","workMode":"Remote","url":"https://www.naukri.com/job-listings-full-stack-123","experienceLevel":"Fresher / Entry level"}
                """;
        MvcResult first = mvc.perform(post("/api/jobs/save-current-page")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("Naukri"))
                .andExpect(jsonPath("$.availabilityStatus").value("OPEN"))
                .andExpect(jsonPath("$.importMethod").value("BROWSER_PAGE"))
                .andReturn();
        JsonNode saved = objectMapper.readTree(first.getResponse().getContentAsString());
        long savedId = saved.get("id").asLong();

        mvc.perform(get("/api/jobs").param("source", "Naukri").param("freshness", "24h")
                        .param("activeOnly", "true").param("minScore", "0").param("location", "Remote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(savedId))
                .andExpect(jsonPath("$[0].workMode").value("Remote"));

        mvc.perform(post("/api/jobs/save-current-page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody.replace("Full Stack Developer", "Full Stack Developer Updated")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedId))
                .andExpect(jsonPath("$.title").value("Full Stack Developer Updated"));
        assertEquals(1, jobs.count());

        mvc.perform(get("/api/stats").param("source", "Naukri").param("freshness", "24h")
                        .param("includeStrongMatches", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1))
                .andExpect(jsonPath("$.newIn24Hours").value(0));
    }

    @Test void importingVisibleNaukriCardsUpsertsByCanonicalUrlAndSourceIdWithoutInventingApplyLinks() throws Exception {
        String payload = """
                [
                  {"title":"Full Stack Developer","company":"Example Labs","location":"Pune","workMode":"Remote","sourceJobId":"78123456","url":"https://www.naukri.com/job-listings-full-stack-78123456?src=search","description":"Build frontend and backend products.","salary":"","requiredSkills":"React, Node.js","experienceRequirements":"0-2 Yrs"},
                  {"title":"Full Stack Developer","company":"Example Labs Updated","location":"Pune","workMode":"Remote","sourceJobId":"78123456","url":"https://www.naukri.com/job-listings-full-stack-78123456?src=recent","description":"Build modern products.","requiredSkills":"React, Node.js"},
                  {"title":"Search page is not a listing","url":"https://www.naukri.com/jobs-in-india?keyword=fullstack"}
                ]
                """;
        mvc.perform(post("/api/jobs/import/naukri-visible")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(3))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.diagnostics", hasSize(1)))
                .andExpect(jsonPath("$.jobs", hasSize(1)))
                .andExpect(jsonPath("$.jobs[0].source").value("Naukri"))
                .andExpect(jsonPath("$.jobs[0].sourceJobId").value("78123456"))
                .andExpect(jsonPath("$.jobs[0].url").value("https://www.naukri.com/job-listings-full-stack-78123456"))
                .andExpect(jsonPath("$.jobs[0].applyUrl").value(""))
                .andExpect(jsonPath("$.jobs[0].applicationUrlVerified").value(false))
                .andExpect(jsonPath("$.jobs[0].publishedAt").value(nullValue()));
        assertEquals(1,jobs.count());
        assertEquals(0,applications.count());
        JobSourceSyncState sync=jobSourceSyncStates.findById("Naukri").orElseThrow();
        assertEquals(3,sync.getFetched());
        assertEquals(1,sync.getImported());
        assertEquals(1,sync.getUpdated());
        assertEquals(1,sync.getRejected());
        assertEquals(1,sync.getDeduplicated());
        assertTrue(sync.getLatestError().contains("individual Naukri job listing"));

        JobListing imported=jobs.findAll().get(0);
        assertEquals("Example Labs Updated",imported.getCompany());
        assertEquals("BROWSER_SEARCH_RESULTS",imported.getImportMethod());
        mvc.perform(get("/api/jobs").param("source","Naukri").param("freshness","24h").param("minScore","0")
                        .param("location","Remote").param("q","Full Stack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$",hasSize(1)))
                .andExpect(jsonPath("$[0].applicationUrlVerified").value(false))
                .andExpect(jsonPath("$[0].sourceJobId").value("78123456"));
        mvc.perform(get("/api/stats").param("source","Naukri").param("freshness","24h")
                        .param("includeStrongMatches","false").param("q","Full Stack").param("location","Remote"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1))
                .andExpect(jsonPath("$.newIn24Hours").value(0));

        JobListing ashbyJob=new JobListing();
        ashbyJob.setSource("Ashby");ashbyJob.setTitle("Associate Engineer");ashbyJob.setCompany("Sample");
        ashbyJob.setUrl("https://jobs.ashbyhq.com/sample/job-1");
        ashbyJob.setApplyUrl("https://jobs.ashbyhq.com/sample/job-1/application");
        ashbyJob.setApplicationUrlVerified(false);
        ashbyJob=jobs.save(ashbyJob);
        mvc.perform(get("/api/jobs/{id}",ashbyJob.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationUrlVerified").value(false));
        assertFalse(jobs.findById(ashbyJob.getId()).orElseThrow().isApplicationUrlVerified());

        mvc.perform(put("/api/jobs/{id}/application-url",imported.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.naukri.com/job-listings-full-stack-78123456?source=apply\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/jobs/{id}/application-url",imported.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://careers.example.test/apply/78123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationUrlVerified").value(true))
                .andExpect(jsonPath("$.applyUrl").value("https://careers.example.test/apply/78123456"));
        assertEquals(0,applications.count());
    }

    @Test void jobsLimitReturnsOnlyTheRequestedTopResults() throws Exception {
        createJob();
        createJob();
        createJob();

        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test void dashboardStatsCanSkipTheUnusedStrongMatchCount() throws Exception {
        mvc.perform(get("/api/stats").param("includeStrongMatches", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(0))
                .andExpect(jsonPath("$.strongMatches").doesNotExist());
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strongMatches").value(0));
    }

    @Test void staleAndClosedJobsAreExcludedFromDashboardAndActiveDiscovery() throws Exception {
        JobListing stale=createJob();
        stale.setUrl("https://jobs.example.test/stale");
        stale.setLastVerifiedAt(Instant.now().minusSeconds(3*24*60*60));
        jobs.save(stale);
        JobListing closed=createJob();
        closed.setUrl("https://jobs.example.test/closed");
        closed.setAvailabilityStatus("CLOSED");
        closed.setClosedAt(Instant.now());
        jobs.save(closed);

        mvc.perform(get("/api/jobs").param("freshness","all").param("activeOnly","true").param("minScore","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(0)));
        mvc.perform(get("/api/stats").param("includeStrongMatches","false").param("freshness","all"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalJobs").value(0));
    }

    @Test void dashboardCountsMatchTheVerifiedFilteredJobList() throws Exception {
        createJob();
        JobListing unverified=createJob();
        unverified.setUrl("https://jobs.example.test/unverified");
        unverified.setAvailabilityStatus("UNVERIFIED");
        unverified.setLastVerifiedAt(null);
        jobs.save(unverified);

        mvc.perform(get("/api/jobs").param("freshness","7d").param("activeOnly","true").param("minScore","0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(1)));
        mvc.perform(get("/api/stats").param("freshness","7d").param("includeStrongMatches","false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalJobs").value(1))
                .andExpect(jsonPath("$.verifiedJobs").value(1));
    }

    @Test void reconciliationMarksStaleRecordsWithoutDeletingSavedJobsOrApplications() throws Exception {
        JobListing stale=createJob();
        stale.setLastVerifiedAt(Instant.now().minusSeconds(3*24*60*60));
        jobs.save(stale);
        mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":" + stale.getId() + ",\"status\":\"SHORTLISTED\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/jobs/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedStale").value(1))
                .andExpect(jsonPath("$.savedRecordsPreserved").value(1))
                .andExpect(jsonPath("$.applicationRecordsPreserved").value(1))
                .andExpect(jsonPath("$.deletedRecords").value(0));

        assertEquals("STALE",jobs.findById(stale.getId()).orElseThrow().getAvailabilityStatus());
        mvc.perform(get("/api/applications")).andExpect(status().isOk()).andExpect(jsonPath("$",hasSize(1)));
    }

    @Test void retiredProviderJobsAreExcludedButApplicationHistoryRemainsVisible() throws Exception {
        createJob();
        int index=0;
        for(String retiredSource:List.of("Remotive","Arbeitnow","Jobicy","RemoteOK")){
            JobListing retired=createJob();
            retired.setSource(retiredSource);
            retired.setUrl("https://legacy.example.test/job/"+(++index));
            jobs.save(retired);
            if("Remotive".equals(retiredSource)){
                JobApplication application=new JobApplication();
                application.setJobId(retired.getId());
                application.setJobTitle(retired.getTitle());
                application.setCompany(retired.getCompany());
                application.setStatus("SUBMITTED");
                applications.save(application);
            }
        }
        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/stats").param("includeStrongMatches", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1));
        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$",hasSize(1)))
                .andExpect(jsonPath("$[0].jobTitle").value("Junior Java Developer"));
        mvc.perform(get("/api/jobs/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobsExcludedRetiredSource").value(4));
    }

    @Test void jobDiagnosticsReportVerificationDiscoveryScoresAndSourceConfiguration() throws Exception {
        Instant now=Instant.now();
        JobListing current=createJob();
        current.setSource("Naukri");
        current.setFirstSeenAt(now);
        current.setLastVerifiedAt(now);
        jobs.save(current);

        JobListing stale=createJob();
        stale.setSource("Naukri");
        stale.setUrl("https://jobs.example.test/stale");
        stale.setFirstSeenAt(now.minus(java.time.Duration.ofDays(8)));
        stale.setLastVerifiedAt(now.minus(java.time.Duration.ofHours(49)));
        jobs.save(stale);

        JobListing missingVerification=createJob();
        missingVerification.setSource("Remotive");
        missingVerification.setUrl("https://legacy.example.test/unverified");
        missingVerification.setFirstSeenAt(now);
        missingVerification.setLastVerifiedAt(null);
        jobs.save(missingVerification);

        mvc.perform(get("/api/jobs/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(3))
                .andExpect(jsonPath("$.jobsPerSource[?(@.source == 'Naukri')].count").value(2))
                .andExpect(jsonPath("$.jobsPerSource[?(@.source == 'Remotive')].count").value(1))
                .andExpect(jsonPath("$.jobsFailingVerificationWindow").value(2))
                .andExpect(jsonPath("$.jobsOutsideDiscoveryWindow").value(1))
                .andExpect(jsonPath("$.jobsWithMatchScoreZero").isNumber())
                .andExpect(jsonPath("$.jobsWithoutVerificationTimestamp").value(1))
                .andExpect(jsonPath("$.jobsExcludedRetiredSource").value(1))
                .andExpect(jsonPath("$.dashboardFreshnessDefault").value("24h"))
                .andExpect(jsonPath("$.verificationWindowHours").value(48))
                .andExpect(jsonPath("$.greenhouseBoardsConfigured").value(2))
                .andExpect(jsonPath("$.leverBoardsConfigured").value(1))
                .andExpect(jsonPath("$.ashbyBoardsConfigured").value(0))
                .andExpect(jsonPath("$.workableAccountsConfigured").value(0))
                .andExpect(jsonPath("$.smartRecruitersCompaniesConfigured").value(0))
                .andExpect(jsonPath("$.publicFeedsEnabled").value(false))
                .andExpect(jsonPath("$.sourcesConfigured").value(true))
                .andExpect(jsonPath("$.sources[?(@.source == 'Greenhouse')].enabled").value(false))
                .andExpect(jsonPath("$.sources[?(@.source == 'Greenhouse')].configured").value(true));
        assertEquals(3,jobs.count());
    }

    @Test void shortlistedJobsPersistWithoutLeavingRecommendations() throws Exception {
        JobListing job = createJob();
        mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":" + job.getId() + ",\"status\":\"SHORTLISTED\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SHORTLISTED"));
        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].jobId").value(job.getId()));
        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(job.getTitle())));
    }

    @Test void geminiAnalysisRequiresExplicitConsent() throws Exception {
        mvc.perform(post("/api/jobs/1/ai-match").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void resumeApplicationDraftRequiresExplicitConsentAndValidResumeId() throws Exception {
        mvc.perform(post("/api/resumes/application-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storageName\":\"00000000-0000-0000-0000-000000000000_resume.txt\",\"jobId\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/resumes/application-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storageName\":\"not-a-saved-resume\",\"jobId\":1,\"consent\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test void resumeApplicationDraftRejectsSensitiveAndUnsupportedFormFields() throws Exception {
        String resume="{\"storageName\":\"00000000-0000-0000-0000-000000000000_resume.txt\",\"jobId\":1,\"consent\":true}";
        mvc.perform(post("/api/resumes/application-draft").contentType(MediaType.APPLICATION_JSON)
                        .content(resume.replace("}",",\"fields\":[]}")))
                .andExpect(status().isBadRequest());
        String sensitiveField="{\"id\":\"f0\",\"type\":\"text\",\"label\":\"Are you legally authorized to work?\",\"name\":\"authorization\",\"context\":\"\",\"placeholder\":\"\",\"required\":true,\"maxLength\":300,\"options\":[]}";
        mvc.perform(post("/api/resumes/application-draft").contentType(MediaType.APPLICATION_JSON)
                        .content(resume.replace("}",",\"fields\":["+sensitiveField+"]}")))
                .andExpect(status().isBadRequest());
    }

    @Test void manualExternalJobUrlIsCanonicalizedAttributedAndDeduplicated() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://EXAMPLE.test/jobs/123?ref=menix#details\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Saved external listing"))
                .andExpect(jsonPath("$.company").value("example.test"))
                .andExpect(jsonPath("$.url").value("https://example.test/jobs/123?ref=menix"))
                .andExpect(jsonPath("$.attributionUrl").value("https://example.test/jobs/123?ref=menix"))
                .andExpect(jsonPath("$.source").value("External Link"));
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.test/jobs/123?ref=menix#another\"}"))
                .andExpect(status().isConflict());
    }

    @Test void portalCapabilitiesDoNotClaimExternalInboxConnections() throws Exception {
        mvc.perform(get("/api/portal-capabilities"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("RemoteOK"))))
                .andExpect(jsonPath("$[?(@.portal == 'LinkedIn')].jobSearch").value("EXTERNAL_LINK"))
                .andExpect(jsonPath("$[?(@.portal == 'LinkedIn')].messagingRead").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[?(@.portal == 'LinkedIn')].messagingSend").value("UNAVAILABLE"))
                .andExpect(jsonPath("$[?(@.portal == 'LinkedIn')].connectionState").value("NOT_CONNECTED"));
    }

    @Test void manualSourceRejectsLocalhostUrl() throws Exception {
        mvc.perform(post("/api/sources/manual").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://localhost/jobs\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void outreachDraftPersistsAndRequiresExplicitSentEvidence() throws Exception {
        MvcResult created = mvc.perform(post("/api/outreach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portal\":\"LinkedIn\",\"contactName\":\"Rae Recruiter\",\"company\":\"Example Co\",\"draftMessage\":\"Hello, I am interested in the role.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        JsonNode record = objectMapper.readTree(created.getResponse().getContentAsString());
        String id = record.get("id").asText();

        mvc.perform(post("/api/outreach/{id}/confirm-sent", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/outreach"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].draftMessage").value("Hello, I am interested in the role."))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
        mvc.perform(post("/api/outreach/{id}/confirm-sent", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\":\"User confirmed sent from LinkedIn on 2026-02-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.confirmationEvidence").isNotEmpty());
        mvc.perform(post("/api/outreach/{id}/confirm-sent", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidence\":\"Duplicate confirmation\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/outreach/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftMessage\":\"Rewrite\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void outreachRejectsNonPublicOrNonHttpsUrls() throws Exception {
        mvc.perform(post("/api/outreach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileUrl\":\"http://example.test/recruiter\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/outreach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobUrl\":\"https://localhost/job\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void applicationCannotBeMarkedSubmittedWithoutConfirmationEvidence() throws Exception {
        JobListing job = createJob();
        mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":" + job.getId() + ",\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isBadRequest());
        MvcResult created = mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":" + job.getId() + ",\"status\":\"SHORTLISTED\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(put("/api/applications/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/applications/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\",\"submissionEvidence\":\"Employer confirmation email received\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submissionEvidence").value("Employer confirmation email received"));
    }

    @Test void notCompletedApplicationCanBeRemovedFromActiveTrackingAndRestarted() throws Exception {
        JobListing job = createJob();
        MvcResult created = mvc.perform(post("/api/applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":" + job.getId() + ",\"status\":\"SHORTLISTED\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(put("/api/applications/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOT_COMPLETED_YET\",\"notes\":\"Application was not finished\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_COMPLETED_YET"))
                .andExpect(jsonPath("$.notes").value("Application was not finished"));
        mvc.perform(post("/api/jobs/{id}/apply", job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.valueOf(id)))
                .andExpect(jsonPath("$.status").value("NEEDS_ATTENTION"));
        mvc.perform(get("/api/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Long.valueOf(id)));
    }

    @Test void autofillSessionsTrackCountsAndRequireVerifiedApplicationSubmission() throws Exception {
        JobListing job = createJob();
        MvcResult applicationResult = mvc.perform(post("/api/jobs/{id}/apply", job.getId()))
                .andExpect(status().isOk()).andReturn();
        String applicationId = objectMapper.readTree(applicationResult.getResponse().getContentAsString()).get("id").asText();
        MvcResult sessionResult = mvc.perform(post("/api/autofill/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":" + job.getId() + ",\"applicationId\":" + applicationId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn();
        String sessionId = objectMapper.readTree(sessionResult.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(put("/api/autofill/sessions/{id}", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NEEDS_REVIEW\",\"fieldsDetected\":3,\"fieldsFilled\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldsDetected").value(3))
                .andExpect(jsonPath("$.fieldsFilled").value(2));
        mvc.perform(put("/api/autofill/sessions/{id}", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\",\"fieldsDetected\":3,\"fieldsFilled\":2}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/applications/{id}", applicationId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\",\"submissionEvidence\":\"Employer confirmation page\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/autofill/sessions/{id}", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUBMITTED\",\"fieldsDetected\":3,\"fieldsFilled\":2,\"diagnostic\":\"Employer confirmation page\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(put("/api/autofill/sessions/{id}", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NEEDS_REVIEW\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/autofill/sessions")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test void autofillCapabilitiesDoNotClaimProviderSpecificSupport() throws Exception {
        mvc.perform(get("/api/autofill/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].automation").value("USER_PERMISSION_REQUIRED"))
                .andExpect(jsonPath("$[1].providerSpecificAdapter").value(false))
                .andExpect(jsonPath("$[?(@.provider == 'LinkedIn')].automation", hasItem("DISABLED")))
                .andExpect(jsonPath("$[?(@.provider == 'Google Forms')].providerSpecificAdapter", hasItem(false)));
    }
}
