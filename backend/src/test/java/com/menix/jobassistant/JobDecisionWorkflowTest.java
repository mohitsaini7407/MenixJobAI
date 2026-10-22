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
    @Autowired OutreachRepository outreach;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach void clearData() { outreach.deleteAll(); applications.deleteAll(); jobs.deleteAll(); }

    private JobListing createJob() {
        JobListing job = new JobListing();
        job.setTitle("Junior Java Developer"); job.setCompany("Test Company");
        job.setLocation("India"); job.setSource("Test"); job.setUrl("https://jobs.example.test/junior-java");
        job.setApplyUrl(job.getUrl()); job.setRequiredSkills("Java, Spring Boot");
        job.setPostedAt(Instant.now()); job.setPublishedAt(LocalDate.now()); job.setExactPostingTime(true);
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
}
