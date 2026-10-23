package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployerBoardConnectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ashbyFixtureMapsJobFieldsAndRemovesHtmlMarkup() throws Exception {
        JsonNode fixture = fixture("ashby.json");
        CompanyBoardConfigurationRepository boards = mock(CompanyBoardConfigurationRepository.class);
        EmployerBoardHttpClient http = fakeHttp(Map.of(
                "ASHBY https://api.ashbyhq.com/posting-api/job-board/sample-board", fixture));
        AshbyJobSourceConnector connector = new AshbyJobSourceConnector(boards, http, "sample-board");

        JsonNode row = connector.fetch().get(0);
        JobListing job = connector.normalize(row);

        assertEquals("Ashby", job.getSource());
        assertEquals("ashby-101", job.getSourceJobId());
        assertEquals("Sample Ashby India", job.getCompany());
        assertEquals("Bengaluru, India", job.getLocation());
        assertEquals("Remote", job.getWorkMode());
        assertEquals("Build reliable services.", job.getDescription());
        assertEquals("https://jobs.ashbyhq.com/sample/ashby-101", job.getUrl());
        assertEquals("https://jobs.ashbyhq.com/sample/ashby-101/application", job.getApplyUrl());
        assertEquals("2026-10-20T09:30:00Z", connector.exactTimestamp(row).toString());
    }

    @Test
    void workableFixtureMapsAccountJobsAndCombinesDescriptionSections() throws Exception {
        JsonNode fixture = fixture("workable.json");
        CompanyBoardConfigurationRepository boards = mock(CompanyBoardConfigurationRepository.class);
        EmployerBoardHttpClient http = fakeHttp(Map.of(
                "WORKABLE https://apply.workable.com/api/v1/widget/accounts/sample-account", fixture));
        WorkableJobSourceConnector connector = new WorkableJobSourceConnector(boards, http, "sample-account");

        JsonNode row = connector.fetch().get(0);
        JobListing job = connector.normalize(row);

        assertEquals("Workable", job.getSource());
        assertEquals("wk-202", job.getSourceJobId());
        assertEquals("Sample Workable India", job.getCompany());
        assertEquals("Pune, Maharashtra, India", job.getLocation());
        assertEquals("Ship customer features. JavaScript experience", job.getDescription());
        assertEquals("Full-time", job.getEmploymentType());
        assertEquals("https://apply.workable.com/sample/j/ABC123/", job.getApplyUrl());
        assertEquals("2026-10-21T10:00:00Z", connector.exactTimestamp(row).toString());
    }

    @Test
    void smartRecruitersFixtureMapsPostingAndPreservesDateOnlyPrecision() throws Exception {
        JsonNode fixture = fixture("smartrecruiters.json");
        CompanyBoardConfigurationRepository boards = mock(CompanyBoardConfigurationRepository.class);
        EmployerBoardHttpClient http = fakeHttp(Map.of(
                "SMARTRECRUITERS https://api.smartrecruiters.com/v1/companies/sample-company/postings?offset=0&limit=100", fixture));
        SmartRecruitersJobSourceConnector connector = new SmartRecruitersJobSourceConnector(boards, http, "sample-company");

        JsonNode row = connector.fetch().get(0);
        JobListing job = connector.normalize(row);

        assertEquals("SmartRecruiters", job.getSource());
        assertEquals("smart-303", job.getSourceJobId());
        assertEquals("Sample SmartRecruiters India", job.getCompany());
        assertEquals("Hyderabad, Telangana, India", job.getLocation());
        assertEquals("Develop internal applications. Java or TypeScript", job.getDescription());
        assertEquals("Full-time", job.getEmploymentType());
        assertEquals("https://jobs.smartrecruiters.com/sample/743999999999999", job.getUrl());
        assertNull(connector.exactTimestamp(row));
        assertEquals(java.time.LocalDate.parse("2026-10-19"), job.getPublishedAt());
    }

    @Test
    void smartRecruitersPaginatesUntilTheReportedTotalIsFetched() throws Exception {
        ArrayNode firstPage = mapper.createArrayNode();
        for (int i = 0; i < 100; i++) firstPage.add(mapper.createObjectNode().put("id", "first-" + i));
        JsonNode first = mapper.createObjectNode().put("totalFound", 101).set("content", firstPage);
        JsonNode second = mapper.readTree("""
                {"totalFound":101,"content":[{"id":"last"}]}
                """);
        CompanyBoardConfigurationRepository boards = mock(CompanyBoardConfigurationRepository.class);
        String firstUrl = "SMARTRECRUITERS https://api.smartrecruiters.com/v1/companies/sample-company/postings?offset=0&limit=100";
        String secondUrl = "SMARTRECRUITERS https://api.smartrecruiters.com/v1/companies/sample-company/postings?offset=100&limit=100";
        EmployerBoardHttpClient http = fakeHttp(Map.of(firstUrl, first, secondUrl, second));
        SmartRecruitersJobSourceConnector connector = new SmartRecruitersJobSourceConnector(boards, http, "sample-company");

        assertEquals(101, connector.fetchBoard("sample-company").size());
        assertTrue(((FixtureHttpClient) http).requested.contains(secondUrl));
    }

    @Test
    void providerPostingTimeRemainsSeparateFromFirstDiscoveryTimeOnImport() throws Exception {
        JsonNode fixture = fixture("ashby.json");
        CompanyBoardConfigurationRepository companyBoards = mock(CompanyBoardConfigurationRepository.class);
        EmployerBoardHttpClient http = fakeHttp(Map.of(
                "ASHBY https://api.ashbyhq.com/posting-api/job-board/sample-board", fixture));
        AshbyJobSourceConnector connector = new AshbyJobSourceConnector(companyBoards, http, "sample-board");
        JobRepository jobs = mock(JobRepository.class);
        when(jobs.findAll()).thenReturn(List.of());
        JobSourceSyncStateRepository syncStates = mock(JobSourceSyncStateRepository.class);
        when(syncStates.findById("Ashby")).thenReturn(java.util.Optional.empty());
        JobSourceService service = new JobSourceService(jobs, syncStates, companyBoards, List.of(connector));

        assertEquals(1, service.syncSource("Ashby").get("imported"));

        var saved = org.mockito.ArgumentCaptor.forClass(JobListing.class);
        verify(jobs).save(saved.capture());
        assertEquals(java.time.Instant.parse("2026-10-20T09:30:00Z"), saved.getValue().getPostedAt());
        assertNotNull(saved.getValue().getFirstSeenAt());
        assertNotEquals(saved.getValue().getPostedAt(), saved.getValue().getFirstSeenAt());
    }

    private EmployerBoardHttpClient fakeHttp(Map<String, JsonNode> responses) {
        return new FixtureHttpClient(mapper, responses);
    }

    private static class FixtureHttpClient extends EmployerBoardHttpClient {
        private final Map<String, JsonNode> responses;
        private final List<String> requested = new java.util.ArrayList<>();

        FixtureHttpClient(ObjectMapper mapper, Map<String, JsonNode> responses) {
            super(mapper);
            this.responses = new LinkedHashMap<>(responses);
        }

        @Override
        JsonNode get(String provider, String endpoint) {
            String key = provider + " " + endpoint;
            requested.add(key);
            JsonNode result = responses.get(key);
            if (result == null) throw new IllegalArgumentException("Unexpected fixture request: " + key);
            return result.deepCopy();
        }
    }

    private JsonNode fixture(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/job-sources/" + name)) {
            assertNotNull(stream, "Missing fixture " + name);
            JsonNode root = mapper.readTree(stream);
            if (root.has("jobs")) return root;
            return root;
        }
    }
}
