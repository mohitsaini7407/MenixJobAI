package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdzunaJobSourceConnectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void savedResponseMapsJobAndPreservesRedirectDestinationWithoutFollowingIt() throws Exception {
        JsonNode fixture = fixture();
        JsonNode row = fixture.path("results").get(0).deepCopy();
        AdzunaJobSourceConnector connector = connector(List.of(new AdzunaSearchProfile("full stack developer", "Jaipur")),
                new FixtureHttpClient(mapper, List.of(fixture)), 200);

        JobListing job = connector.normalize(row);

        assertEquals("Adzuna", job.getSource());
        assertEquals("adzuna-401", job.getSourceJobId());
        assertEquals("Full Stack Developer", job.getTitle());
        assertEquals("Sample Adzuna India", job.getCompany());
        assertEquals("Jaipur, Rajasthan", job.getLocation());
        assertEquals("Build web applications with React & Java.", job.getDescription());
        assertEquals("https://www.adzuna.in/land/ad/401?utm_source=api&token=sample", job.getUrl());
        assertEquals("https://www.adzuna.in/land/ad/401?utm_source=api&token=sample", job.getApplyUrl());
        assertEquals("full_time\npermanent", job.getEmploymentType());
        assertEquals("600000 - 900000", job.getSalary());
        assertEquals("2026-10-22T09:30:00Z", connector.exactTimestamp(row).toString());
    }

    @Test
    void dateOnlyProviderPostingTimeDoesNotBecomeAnExactTimestamp() throws Exception {
        AdzunaJobSourceConnector connector = connector(
                List.of(new AdzunaSearchProfile("full stack developer", "Jaipur")),
                new FixtureHttpClient(mapper, List.of(fixture())), 200);
        JsonNode row = mapper.readTree("""
                {"id":"date-only","title":"Developer","company":{"display_name":"Example"},
                 "location":{"display_name":"Jaipur, Rajasthan"},"redirect_url":"https://jobs.example.com/1",
                 "created":"2026-10-22"}
                """);

        JobListing job = connector.normalize(row);

        assertNull(connector.exactTimestamp(row));
        assertEquals(LocalDate.parse("2026-10-22"), job.getPublishedAt());
        assertFalse(job.isExactPostingTime());
    }

    @Test
    void fetchUsesSavedProfileAndStopsAfterThreePages() throws Exception {
        JsonNode fullPage = mapper.readTree("{\"results\":["
                + String.join(",", java.util.Collections.nCopies(50, "{\"id\":\"job\"}")) + "]}");
        FixtureHttpClient http = new FixtureHttpClient(mapper, List.of(fullPage, fullPage, fullPage));
        AdzunaJobSourceConnector connector = connector(
                List.of(new AdzunaSearchProfile("software engineer", "remote")), http, 200);

        assertEquals(150, connector.fetch().size());
        assertEquals(3, http.requested.size());
        assertTrue(http.requested.get(0).startsWith("https://api.adzuna.com/v1/api/jobs/in/search/1?"));
        assertTrue(http.requested.get(0).contains("what=software+engineer"));
        assertTrue(http.requested.get(0).contains("where=remote"));
        assertTrue(http.requested.get(0).contains("results_per_page=50"));
        assertTrue(http.requested.get(0).contains("max_days_old=7&sort_by=date&content-type=application/json"));
    }

    @Test
    void dailyUsageStopsRequestsAtConfiguredCapAndPersistsCalls() throws Exception {
        JsonNode fullPage = mapper.readTree("{\"results\":["
                + String.join(",", java.util.Collections.nCopies(50, "{\"id\":\"job\"}")) + "]}");
        AdzunaDailyApiUsageRepository repository = mock(AdzunaDailyApiUsageRepository.class);
        Map<LocalDate, AdzunaDailyApiUsage> savedUsage = new java.util.HashMap<>();
        when(repository.findById(any(LocalDate.class))).thenAnswer(call -> Optional.ofNullable(savedUsage.get(call.getArgument(0))));
        when(repository.save(any(AdzunaDailyApiUsage.class))).thenAnswer(call -> {
            AdzunaDailyApiUsage row = call.getArgument(0);
            savedUsage.put(row.getUsageDate(), row);
            return row;
        });
        AdzunaDailyUsageService usage = new AdzunaDailyUsageService(repository, 1);
        FixtureHttpClient http = new FixtureHttpClient(mapper, List.of(fullPage, fullPage));
        AdzunaJobSourceConnector connector = connector(
                List.of(new AdzunaSearchProfile("software engineer", "India")), http, usage);

        assertEquals(50, connector.fetch().size());
        assertEquals(1, http.requested.size());
        assertEquals(1, usage.status().get("callsToday"));
        assertEquals(0, usage.status().get("remainingCalls"));
    }

    @Test
    void absentCredentialsShowNotConfiguredAndDoNotMakeSyncRequests() throws Exception {
        AdzunaSearchProfileRepository profileRepository = mock(AdzunaSearchProfileRepository.class);
        AdzunaSearchProfileService profiles = new AdzunaSearchProfileService(profileRepository);
        AdzunaDailyUsageService usage = new AdzunaDailyUsageService(mock(AdzunaDailyApiUsageRepository.class), 200);
        FixtureHttpClient http = new FixtureHttpClient(mapper, List.of(fixture()));
        AdzunaJobSourceConnector connector = new AdzunaJobSourceConnector(http, profiles, usage, "", "");
        JobSourceService service = new JobSourceService(mock(JobRepository.class),
                mock(JobSourceSyncStateRepository.class), mock(CompanyBoardConfigurationRepository.class), List.of(connector));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicJobFeedsEnabled", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) service.status().get("sources");
        Map<String, Object> adzuna = sources.stream().filter(row -> "Adzuna".equals(row.get("source"))).findFirst().orElseThrow();

        assertEquals("Not configured", adzuna.get("status"));
        assertEquals(0, service.sync().get("sourcesChecked"));
        assertTrue(http.requested.isEmpty());
    }

    @Test
    void searchProfilesAreInitializedAndPersistEditableValues() {
        AdzunaSearchProfileRepository repository = mock(AdzunaSearchProfileRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.saveAll(any())).thenAnswer(call -> call.getArgument(0));
        AdzunaSearchProfileService service = new AdzunaSearchProfileService(repository);

        List<AdzunaSearchProfile> defaults = service.list();
        List<AdzunaSearchProfile> replacement = service.replace(List.of(
                Map.of("what", "Java developer", "where", "Jaipur")));

        assertEquals(3, defaults.size());
        assertEquals("full stack developer", defaults.get(0).getWhat());
        assertEquals(1, replacement.size());
        assertEquals("Java developer", replacement.get(0).getWhat());
        assertEquals("Jaipur", replacement.get(0).getWhere());
        verify(repository).deleteAllInBatch();
    }

    @Test
    void secondaryTitleCompanyCityKeyDeduplicatesAdzunaAgainstExistingJobs() throws Exception {
        JsonNode fixture = fixture();
        AdzunaJobSourceConnector connector = connector(
                List.of(new AdzunaSearchProfile("full stack developer", "Jaipur")),
                new FixtureHttpClient(mapper, List.of(fixture)), 200);
        JobListing existing = new JobListing();
        existing.setId(11L);
        existing.setSource("Manual");
        existing.setTitle("Full Stack Developer");
        existing.setCompany("Sample Adzuna India");
        existing.setLocation("Jaipur, Rajasthan");
        existing.setUrl("https://jobs.example.com/full-stack");
        JobRepository jobs = mock(JobRepository.class);
        when(jobs.findAll()).thenReturn(List.of(existing));
        JobSourceSyncStateRepository states = mock(JobSourceSyncStateRepository.class);
        when(states.findById("Adzuna")).thenReturn(Optional.empty());
        JobSourceService service = new JobSourceService(jobs, states, mock(CompanyBoardConfigurationRepository.class), List.of(connector));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicJobFeedsEnabled", true);

        Map<String, Object> result = service.syncSource("Adzuna");

        assertEquals(0, result.get("imported"));
        assertEquals(1, result.get("deduplicated"));
        verify(jobs).save(existing);
        assertEquals("https://www.adzuna.in/land/ad/401?utm_source=api&token=sample", existing.getApplyUrl());
        assertEquals("https://www.adzuna.in/land/ad/401?utm_source=api&token=sample", existing.getUrl());
    }

    private AdzunaJobSourceConnector connector(List<AdzunaSearchProfile> configuredProfiles,
                                                EmployerBoardHttpClient http, int cap) {
        AdzunaDailyApiUsageRepository repository = mock(AdzunaDailyApiUsageRepository.class);
        AdzunaDailyUsageService usage = new AdzunaDailyUsageService(repository, cap);
        return connector(configuredProfiles, http, usage);
    }

    private AdzunaJobSourceConnector connector(List<AdzunaSearchProfile> configuredProfiles,
                                                EmployerBoardHttpClient http, AdzunaDailyUsageService usage) {
        AdzunaSearchProfileRepository repository = mock(AdzunaSearchProfileRepository.class);
        when(repository.findAll()).thenReturn(configuredProfiles);
        AdzunaSearchProfileService profiles = new AdzunaSearchProfileService(repository);
        return new AdzunaJobSourceConnector(http, profiles, usage, "test-app-id", "test-app-key");
    }

    private JsonNode fixture() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/job-sources/adzuna.json")) {
            assertNotNull(stream);
            return mapper.readTree(stream);
        }
    }

    private static class FixtureHttpClient extends EmployerBoardHttpClient {
        private final List<JsonNode> responses;
        private final List<String> requested = new ArrayList<>();
        private int index;

        FixtureHttpClient(ObjectMapper mapper, List<JsonNode> responses) {
            super(mapper);
            this.responses = responses;
        }

        @Override JsonNode get(String provider, String endpoint) {
            assertEquals("Adzuna", provider);
            requested.add(endpoint);
            return responses.get(Math.min(index++, responses.size() - 1)).deepCopy();
        }

        @Override JsonNode get(String provider, String endpoint, java.util.function.BooleanSupplier allowRequest) {
            if (!allowRequest.getAsBoolean()) throw new RequestQuotaExceededException(provider);
            return get(provider, endpoint);
        }
    }
}
