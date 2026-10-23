package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanyBoardServiceTest {
    @Test
    void companyBoardMustPassTestBeforeItCanBeEnabled() throws Exception {
        CompanyBoardConfigurationRepository repository = mock(CompanyBoardConfigurationRepository.class);
        when(repository.findByProviderIgnoreCase("ASHBY")).thenReturn(List.of());
        when(repository.save(any(CompanyBoardConfiguration.class))).thenAnswer(invocation -> {
            CompanyBoardConfiguration board = invocation.getArgument(0);
            board.setId(1L);
            return board;
        });
        CompanyBoardProviderConnector connector = new TestBoardConnector("ASHBY", 6, null);
        CompanyBoardService service = new CompanyBoardService(repository, List.of(connector));

        CompanyBoardConfiguration created = service.add(Map.of(
                "provider", "ashby", "identifier", "sample-board", "company", "Sample Company"));
        when(repository.findById(1L)).thenReturn(Optional.of(created));
        assertEquals("UNVERIFIED", created.getTestStatus());
        assertFalse(created.isEnabled());
        assertThrows(IllegalArgumentException.class,
                () -> service.update(created.getId(), Map.of("enabled", true)));

        Map<String, Object> test = service.test(created.getId());
        assertEquals(true, test.get("ok"));
        assertEquals(6, test.get("jobsFound"));
        assertEquals("VERIFIED", created.getTestStatus());
        assertFalse(created.isEnabled());

        service.update(created.getId(), Map.of("enabled", true));
        assertTrue(created.isEnabled());
    }

    @Test
    void failedBoardTestKeepsBoardUnverifiedAndReportsTheError() {
        CompanyBoardConfiguration board = new CompanyBoardConfiguration();
        board.setId(2L);
        board.setProvider("WORKABLE");
        board.setIdentifier("invalid-board");
        board.setCompany("Example");
        CompanyBoardConfigurationRepository repository = mock(CompanyBoardConfigurationRepository.class);
        when(repository.findById(2L)).thenReturn(Optional.of(board));
        when(repository.save(any(CompanyBoardConfiguration.class))).thenReturn(board);
        CompanyBoardProviderConnector connector = new TestBoardConnector("WORKABLE", 0, new IllegalStateException("HTTP 404"));
        CompanyBoardService service = new CompanyBoardService(repository, List.of(connector));

        Map<String, Object> result = service.test(2L);

        assertEquals(false, result.get("ok"));
        assertEquals("HTTP 404", result.get("message"));
        assertEquals("UNVERIFIED", board.getTestStatus());
        assertEquals("HTTP 404", board.getLastError());
        assertFalse(board.isEnabled());
    }

    @Test
    void rejectsIdentifiersThatCouldEscapeTheProviderHostPath() {
        CompanyBoardConfigurationRepository repository = mock(CompanyBoardConfigurationRepository.class);
        CompanyBoardService service = new CompanyBoardService(repository, List.of(new TestBoardConnector("ASHBY", 0, null)));

        assertThrows(IllegalArgumentException.class, () -> service.add(Map.of(
                "provider", "ASHBY", "identifier", "../private", "company", "Example")));
    }

    @Test
    void publicHttpsValidationRejectsLocalAndSharedAddressRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceRegistryService.validatePublicHttps("https://127.0.0.1/jobs"));
        assertThrows(IllegalArgumentException.class,
                () -> SourceRegistryService.validatePublicHttps("https://100.64.0.1/jobs"));
    }

    private static class TestBoardConnector implements CompanyBoardProviderConnector {
        private final int jobs;
        private final Exception error;
        private final String provider;

        TestBoardConnector(String provider, int jobs, Exception error) {
            this.provider = provider;
            this.jobs = jobs;
            this.error = error;
        }

        public String sourceName() { return switch(provider){case "WORKABLE"->"Workable";case "SMARTRECRUITERS"->"SmartRecruiters";default->"Ashby";}; }
        public String providerKey() { return provider; }
        public String attributionUrl() { return "https://api.example.test/"; }
        public List<JsonNode> fetch() { return List.of(); }
        public List<JsonNode> fetchBoard(String identifier) { return List.of(); }
        public int testBoard(String identifier) throws Exception {
            if (error != null) throw error;
            return jobs;
        }
        public JobListing normalize(JsonNode item) { return new JobListing(); }
    }
}
