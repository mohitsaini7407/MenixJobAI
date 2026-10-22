package com.menix.jobassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JobSourceConnectorTest {
    @Test void failingRegisteredProviderReturnsAnExplicitSyncError() {
        JobSourceConnector failing = new JobSourceConnector() {
            public String sourceName() { return "Remotive"; }
            public String attributionUrl() { return "https://example.test/jobs"; }
            public List<com.fasterxml.jackson.databind.JsonNode> fetch() { throw new IllegalStateException("Provider unavailable"); }
            public JobListing normalize(com.fasterxml.jackson.databind.JsonNode item) { throw new AssertionError("No row should be normalized."); }
        };
        JobRepository jobs = mock(JobRepository.class);
        JobSourceSyncStateRepository states = mock(JobSourceSyncStateRepository.class);
        JobSourceSyncState previous = new JobSourceSyncState("Remotive");
        previous.setLastSuccessfulSyncAt(java.time.Instant.parse("2026-10-20T08:00:00Z"));
        when(states.findById(anyString())).thenReturn(Optional.of(previous));
        JobSourceService service = new JobSourceService(jobs, states, List.of(failing));

        Map<String, Object> result = service.syncSource("Remotive");

        assertEquals(0, result.get("imported"));
        assertEquals("Provider unavailable", result.get("error"));
        var saved=org.mockito.ArgumentCaptor.forClass(JobSourceSyncState.class);
        verify(states, times(2)).save(saved.capture());
        assertEquals("Provider unavailable",saved.getAllValues().get(1).getLatestError());
        assertEquals(previous.getLastSuccessfulSyncAt(),saved.getAllValues().get(1).getLastSuccessfulSyncAt());
    }

    @Test void removedRemoteOkProviderIsNotAdvertisedOrRegistered() {
        JobSourceSyncStateRepository states = mock(JobSourceSyncStateRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());
        JobSourceService service = new JobSourceService(mock(JobRepository.class), states, List.of());

        assertEquals("No provider connector is registered for RemoteOK.", service.syncSource("RemoteOK").get("error"));
    }

    @Test void successfulSyncMarksObservedJobsOpenAndVerified() throws Exception {
        var mapper = new ObjectMapper();
        var row = mapper.readTree("""
                {"id":42,"title":"Junior Java Developer","company_name":"Example Co","url":"https://example.test/job/42",
                 "publication_date":"2026-10-20T09:30:00Z","tags":["Java"]}
                """);
        JobSourceConnector connector = new JobSourceConnector() {
            public String sourceName() { return "Remotive"; }
            public String attributionUrl() { return "https://remotive.com/remote-jobs"; }
            public List<com.fasterxml.jackson.databind.JsonNode> fetch() { return List.of(row); }
            public JobListing normalize(com.fasterxml.jackson.databind.JsonNode item) {
                JobListing job = new JobListing();job.setSource(sourceName());job.setSourceJobId("42");
                job.setUrl(item.path("url").asText());job.setTitle(item.path("title").asText());
                job.setCompany(item.path("company_name").asText());job.setAttributionUrl(attributionUrl());
                return job;
            }
            public java.time.Instant exactTimestamp(com.fasterxml.jackson.databind.JsonNode item) {
                return java.time.Instant.parse(item.path("publication_date").asText());
            }
        };
        JobRepository jobs = mock(JobRepository.class);
        when(jobs.findAll()).thenReturn(List.of());
        JobSourceSyncStateRepository states = mock(JobSourceSyncStateRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());
        JobSourceService service = new JobSourceService(jobs, states, List.of(connector));

        Map<String,Object> result=service.syncSource("Remotive");

        assertEquals(1,result.get("imported"));
        var saved=org.mockito.ArgumentCaptor.forClass(JobListing.class);
        verify(jobs).save(saved.capture());
        assertEquals("OPEN",saved.getValue().getAvailabilityStatus());
        assertEquals("API",saved.getValue().getImportMethod());
        assertNotNull(saved.getValue().getFirstSeenAt());
        assertNotNull(saved.getValue().getLastSeenAt());
        assertNotNull(saved.getValue().getLastVerifiedAt());
    }

    @Test void successfulObservationUpdatesExistingCanonicalUrlInsteadOfDuplicating() throws Exception {
        var mapper=new ObjectMapper();
        var row=mapper.readTree("""
                {"id":42,"title":"Junior Java Developer","company_name":"Example Co","url":"https://example.test/job/42",
                 "publication_date":"2026-10-20T09:30:00Z"}
                """);
        JobSourceConnector connector=new JobSourceConnector() {
            public String sourceName(){return "Remotive";}
            public String attributionUrl(){return "https://remotive.com/remote-jobs";}
            public List<com.fasterxml.jackson.databind.JsonNode> fetch(){return List.of(row);}
            public JobListing normalize(com.fasterxml.jackson.databind.JsonNode item){JobListing job=new JobListing();job.setSource(sourceName());job.setUrl(item.path("url").asText());job.setSourceJobId("42");job.setTitle(item.path("title").asText());return job;}
            public java.time.Instant exactTimestamp(com.fasterxml.jackson.databind.JsonNode item){return java.time.Instant.parse(item.path("publication_date").asText());}
        };
        JobListing existing=new JobListing();existing.setSource("Remotive");existing.setUrl("https://example.test/job/42");existing.setAvailabilityStatus("UNVERIFIED");
        JobRepository jobs=mock(JobRepository.class);
        when(jobs.findAll()).thenReturn(List.of(existing));
        JobSourceSyncStateRepository states=mock(JobSourceSyncStateRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());

        Map<String,Object> result=new JobSourceService(jobs,states,List.of(connector)).syncSource("Remotive");

        assertEquals(0,result.get("imported"));
        assertEquals(1,result.get("updated"));
        assertEquals("OPEN",existing.getAvailabilityStatus());
        assertNotNull(existing.getLastSeenAt());
        verify(jobs,never()).save(argThat(job->job!=existing));
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

    @Test void postingTimestampIsConvertedToUtcWithoutLosingItsInstant() throws Exception {
        RemotiveJobSourceConnector connector=new RemotiveJobSourceConnector("https://example.test/jobs");
        var row=new ObjectMapper().readTree("""
                {"title":"Developer","company_name":"Example","url":"https://example.test/job/2",
                 "publication_date":"2026-10-20T15:30:00+05:30"}
                """);
        assertEquals(java.time.Instant.parse("2026-10-20T10:00:00Z"),connector.exactTimestamp(row));
    }

    @Test void failedLatestAttemptIsNotReportedAsConnectedDespiteOlderSuccess() {
        Map<String,Object> remotive=Map.of("source","Remotive","enabled",true,
                "lastSuccessfulSync","2026-10-20T08:00:00Z","error","Provider timeout");
        Map<String,Object> status=Map.of("sources",List.of(remotive));
        Map<String,Object> capability=new PortalCapabilityRegistry().list(status).stream()
                .filter(row->"Remotive".equals(row.get("portal"))).findFirst().orElseThrow();
        assertEquals("ERROR",capability.get("connectionState"));
        assertEquals("Provider timeout",capability.get("error"));
    }
}
