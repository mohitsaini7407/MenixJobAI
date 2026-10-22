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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:menix-workflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class JobDecisionWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired JobRepository jobs;
    @Autowired ApplicationRepository applications;
    @Autowired AutofillSessionRepository autofillSessions;
    @Autowired OutreachRepository outreach;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach void clearData() { autofillSessions.deleteAll(); outreach.deleteAll(); applications.deleteAll(); jobs.deleteAll(); }

    private JobListing createJob() {
        JobListing job = new JobListing();
        job.setTitle("Junior Java Developer"); job.setCompany("Test Company");
        job.setLocation("India"); job.setSource("Test"); job.setUrl("https://jobs.example.test/junior-java");
        job.setApplyUrl(job.getUrl()); job.setRequiredSkills("Java, Spring Boot");
        job.setPostedAt(Instant.now()); job.setPublishedAt(LocalDate.now(ZoneOffset.UTC)); job.setExactPostingTime(true);
        job.setAvailabilityStatus("OPEN");job.setImportMethod("API");job.setLastSeenAt(Instant.now());job.setLastVerifiedAt(Instant.now());
        return jobs.save(job);
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

    @Test void removedRemoteOkListingsAreExcludedFromDiscoveryAndDashboardCounts() throws Exception {
        createJob();
        JobListing oldRemoteOkJob = createJob();
        oldRemoteOkJob.setSource("RemoteOK");
        oldRemoteOkJob.setUrl("https://remoteok.example.test/legacy-job");
        jobs.save(oldRemoteOkJob);

        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/stats").param("includeStrongMatches", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(1));
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
