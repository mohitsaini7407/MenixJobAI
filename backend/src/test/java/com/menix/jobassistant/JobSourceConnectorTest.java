package com.menix.jobassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class JobSourceConnectorTest {
    @Test void emptyConfiguredSourceListDoesNotBreakSyncOrAdvertiseRetiredSources() {
        JobRepository jobs=mock(JobRepository.class);
        JobSourceSyncStateRepository states=mock(JobSourceSyncStateRepository.class);
        CompanyBoardConfigurationRepository companyBoards=mock(CompanyBoardConfigurationRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());
        JobSourceService service=new JobSourceService(jobs,states,companyBoards,List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicJobFeedsEnabled",false);

        Map<String,Object> result=service.sync();
        Map<String,Object> status=service.status();

        assertEquals(0,result.get("sourcesChecked"));
        assertEquals("Public feeds are disabled. Enable permitted employer-board feeds with PUBLIC_JOB_FEEDS_ENABLED=true.",result.get("message"));
        assertEquals(false,status.get("publicFeedsEnabled"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> sources=(List<Map<String,Object>>)status.get("sources");
        assertFalse(sources.stream().anyMatch(row->Set.of("Remotive","Arbeitnow","Jobicy").contains(row.get("source"))));
        assertTrue(sources.stream().filter(row->Set.of("Greenhouse","Lever").contains(row.get("source")))
                .noneMatch(row->Boolean.TRUE.equals(row.get("enabled"))));

        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicJobFeedsEnabled",true);
        Map<String,Object> enabledWithoutBoards=service.sync();
        assertEquals(0,enabledWithoutBoards.get("sourcesChecked"));
        assertEquals("No sources configured. Add sources in Sources.",enabledWithoutBoards.get("message"));
    }

    @Test void configuredCompanyBoardsAppearWithIndependentSourceHealth() {
        CompanyBoardConfiguration board=new CompanyBoardConfiguration();
        board.setProvider("ASHBY");board.setIdentifier("sample-board");board.setCompany("Sample");board.setEnabled(true);
        CompanyBoardConfigurationRepository companyBoards=mock(CompanyBoardConfigurationRepository.class);
        when(companyBoards.findByProviderIgnoreCase("ASHBY")).thenReturn(List.of(board));
        when(companyBoards.findByProviderIgnoreCaseAndEnabledTrue("ASHBY")).thenReturn(List.of(board));
        JobSourceSyncStateRepository states=mock(JobSourceSyncStateRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());
        JobSourceService service=new JobSourceService(mock(JobRepository.class),states,companyBoards,List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"publicJobFeedsEnabled",true);

        @SuppressWarnings("unchecked") List<Map<String,Object>> sources=(List<Map<String,Object>>)service.status().get("sources");
        Map<String,Object> ashby=sources.stream().filter(row->"Ashby".equals(row.get("source"))).findFirst().orElseThrow();

        assertEquals(true,ashby.get("configured"));
        assertEquals(true,ashby.get("enabled"));
        assertEquals(1,ashby.get("configuredBoards"));
        assertEquals(1,ashby.get("enabledBoards"));
    }

    @Test void failingRegisteredProviderReturnsAnExplicitSyncError() {
        JobSourceConnector failing = new JobSourceConnector() {
            public String sourceName() { return "Greenhouse"; }
            public String attributionUrl() { return "https://example.test/jobs"; }
            public List<com.fasterxml.jackson.databind.JsonNode> fetch() { throw new IllegalStateException("Provider unavailable"); }
            public JobListing normalize(com.fasterxml.jackson.databind.JsonNode item) { throw new AssertionError("No row should be normalized."); }
        };
        JobRepository jobs = mock(JobRepository.class);
        JobSourceSyncStateRepository states = mock(JobSourceSyncStateRepository.class);
        CompanyBoardConfigurationRepository companyBoards = mock(CompanyBoardConfigurationRepository.class);
        JobSourceSyncState previous = new JobSourceSyncState("Greenhouse");
        previous.setLastSuccessfulSyncAt(java.time.Instant.parse("2026-10-20T08:00:00Z"));
        when(states.findById(anyString())).thenReturn(Optional.of(previous));
        JobSourceService service = new JobSourceService(jobs, states, companyBoards, List.of(failing));

        Map<String, Object> result = service.syncSource("Greenhouse");

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
        JobSourceService service = new JobSourceService(mock(JobRepository.class), states, mock(CompanyBoardConfigurationRepository.class), List.of());

        assertEquals("This source does not support provider sync.", service.syncSource("RemoteOK").get("error"));
        assertEquals("This source does not support provider sync.", service.syncSource("Remotive").get("error"));
        assertEquals("This source does not support provider sync.", service.syncSource("Arbeitnow").get("error"));
        assertEquals("This source does not support provider sync.", service.syncSource("Jobicy").get("error"));
    }

    @Test void successfulSyncMarksObservedJobsOpenAndVerified() throws Exception {
        var mapper = new ObjectMapper();
        var row = mapper.readTree("""
                {"id":42,"title":"Junior Java Developer","company_name":"Example Co","url":"https://example.test/job/42",
                 "publication_date":"2026-10-20T09:30:00Z","tags":["Java"]}
                """);
        JobSourceConnector connector = new JobSourceConnector() {
            public String sourceName() { return "Greenhouse"; }
            public String attributionUrl() { return "https://boards.greenhouse.io/"; }
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
        CompanyBoardConfigurationRepository companyBoards = mock(CompanyBoardConfigurationRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());
        JobSourceService service = new JobSourceService(jobs, states, companyBoards, List.of(connector));

        Map<String,Object> result=service.syncSource("Greenhouse");

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
            public String sourceName(){return "Greenhouse";}
            public String attributionUrl(){return "https://boards.greenhouse.io/";}
            public List<com.fasterxml.jackson.databind.JsonNode> fetch(){return List.of(row);}
            public JobListing normalize(com.fasterxml.jackson.databind.JsonNode item){JobListing job=new JobListing();job.setSource(sourceName());job.setUrl(item.path("url").asText());job.setSourceJobId("42");job.setTitle(item.path("title").asText());return job;}
            public java.time.Instant exactTimestamp(com.fasterxml.jackson.databind.JsonNode item){return java.time.Instant.parse(item.path("publication_date").asText());}
        };
        JobListing existing=new JobListing();existing.setSource("Greenhouse");existing.setUrl("https://example.test/job/42");existing.setAvailabilityStatus("UNVERIFIED");
        JobRepository jobs=mock(JobRepository.class);
        when(jobs.findAll()).thenReturn(List.of(existing));
        JobSourceSyncStateRepository states=mock(JobSourceSyncStateRepository.class);
        CompanyBoardConfigurationRepository companyBoards=mock(CompanyBoardConfigurationRepository.class);
        when(states.findById(anyString())).thenReturn(Optional.empty());

        Map<String,Object> result=new JobSourceService(jobs,states,companyBoards,List.of(connector)).syncSource("Greenhouse");

        assertEquals(0,result.get("imported"));
        assertEquals(1,result.get("updated"));
        assertEquals("OPEN",existing.getAvailabilityStatus());
        assertNotNull(existing.getLastSeenAt());
        verify(jobs,never()).save(argThat(job->job!=existing));
    }

    @Test void failedLatestAttemptIsNotReportedAsConnectedDespiteOlderSuccess() {
        Map<String,Object> greenhouse=Map.of("source","Greenhouse","enabled",true,"configured",true,
                "lastSuccessfulSync","2026-10-20T08:00:00Z","error","Provider timeout");
        Map<String,Object> status=Map.of("sources",List.of(greenhouse));
        Map<String,Object> capability=new PortalCapabilityRegistry().list(status).stream()
                .filter(row->"Greenhouse".equals(row.get("portal"))).findFirst().orElseThrow();
        assertEquals("ERROR",capability.get("connectionState"));
        assertEquals("Provider timeout",capability.get("error"));
        assertFalse(new PortalCapabilityRegistry().list(status).stream()
                .anyMatch(row->Set.of("Remotive","Arbeitnow","Jobicy").contains(row.get("portal"))));
    }
}
