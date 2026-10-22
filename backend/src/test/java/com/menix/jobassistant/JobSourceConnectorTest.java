package com.menix.jobassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class JobSourceConnectorTest {
    @Test void failingRegisteredProviderReturnsAnExplicitSyncError() {
        JobSourceConnector failing = new JobSourceConnector() {
            public String sourceName() { return "Remotive"; }
            public String attributionUrl() { return "https://example.test/jobs"; }
            public List<com.fasterxml.jackson.databind.JsonNode> fetch() { throw new IllegalStateException("Provider unavailable"); }
            public JobListing normalize(com.fasterxml.jackson.databind.JsonNode item) { throw new AssertionError("No row should be normalized."); }
        };
        JobSourceService service = new JobSourceService(mock(JobRepository.class), List.of(failing));

        Map<String, Object> result = service.syncSource("Remotive");

        assertEquals(0, result.get("imported"));
        assertEquals("Provider unavailable", result.get("error"));
    }

    @Test void removedRemoteOkProviderIsNotAdvertisedOrRegistered() {
        JobSourceService service = new JobSourceService(mock(JobRepository.class), List.of());

        assertEquals("No provider connector is registered for RemoteOK.", service.syncSource("RemoteOK").get("error"));
    }

    @Test void remotiveConnectorPreservesProviderAttributionAndPostingDate() throws Exception {
        RemotiveJobSourceConnector connector = new RemotiveJobSourceConnector("https://example.test/jobs");
        var row = new ObjectMapper().readTree("""
                {"title":"Junior Java Developer","company_name":"Example Co","url":"https://example.test/job/1",
                 "publication_date":"2026-10-20T09:30:00Z","tags":["Java","Spring Boot"]}
                """);

        JobListing job = connector.normalize(row);

        assertEquals("Remotive", job.getSource());
        assertEquals("https://remotive.com/remote-jobs", job.getAttributionUrl());
        assertEquals("Junior Java Developer", job.getTitle());
        assertEquals("Java, Spring Boot", job.getRequiredSkills());
        assertEquals("2026-10-20", job.getPublishedAt().toString());
        assertNotNull(connector.exactTimestamp(row));
    }
}
