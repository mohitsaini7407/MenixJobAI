package com.menix.jobassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CareerPageServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsJobPostingObjectsFromGraphAndNestedArrays() throws Exception {
        String html;
        try (var input = getClass().getResourceAsStream("/career-pages/jobposting.jsonld.html")) {
            assertNotNull(input);
            html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<CareerPageService.CareerPosting> postings = CareerPageService.parsePostings(
                html, "https://careers.example.test/openings", mapper);

        assertEquals(2, postings.size());
        CareerPageService.CareerPosting active = postings.get(0);
        assertEquals("Junior Full Stack Engineer", active.title());
        assertEquals("Example Systems", active.company());
        assertEquals("Jaipur, Rajasthan, India", active.location());
        assertEquals("FULL_TIME, CONTRACTOR", active.employmentType());
        assertEquals("Build accessible web applications.", active.description());
        assertEquals("sample-active-01", active.identifier());
        assertEquals("https://careers.example.test/jobs/full-stack-01", active.url());
        assertEquals("https://careers.example.test/jobs/full-stack-01/apply", active.applyUrl());
        assertEquals(Instant.parse("2026-10-20T00:00:00Z"), active.postedAt());
        assertFalse(active.exactPostedAt());
        assertEquals(Instant.parse("2099-12-31T23:59:59.999999999Z"), active.validThrough());

        CareerPageService.CareerPosting expired = postings.get(1);
        assertEquals("Closed Example Role", expired.title());
        assertEquals("Pune, India", expired.location());
        assertTrue(expired.validThrough().isBefore(Instant.now()));
        assertEquals(expired.url(), expired.applyUrl());
    }

    @Test
    void ignoresMalformedJsonLdAndNonPostingStructuredData() {
        String html = """
                <script type="application/ld+json">{invalid json</script>
                <script type="application/ld+json">{"@type":"Organization","name":"Example"}</script>
                """;

        assertTrue(CareerPageService.parsePostings(html, "https://careers.example.test/jobs", mapper).isEmpty());
    }

    @Test
    void expiredPostingIsMappedClosedAndRobotsAllowRulesOverrideLessSpecificDisallow() throws Exception {
        String html = """
                <script type="application/ld+json">
                {"@type":"JobPosting","title":"Expired role","validThrough":"2000-01-01",
                 "url":"https://careers.example.test/jobs/expired"}
                </script>
                """;
        CareerPageService.CareerPosting posting = CareerPageService.parsePostings(
                html, "https://careers.example.test/jobs", mapper).get(0);
        JobListing job = new JobListing();
        Instant now = Instant.now();
        CareerPageService.mapToJob(posting, job, "https://careers.example.test/jobs", now);
        assertEquals("CLOSED", job.getAvailabilityStatus());
        assertEquals(now, job.getClosedAt());
        assertEquals(now, job.getLastVerifiedAt());
        assertFalse(job.isApplicationUrlVerified());

        CareerPageService.RobotsPolicy policy = CareerPageService.parseRobots("""
                User-agent: *
                Disallow: /private
                Allow: /private/public
                """);
        assertFalse(policy.allowed("/private/apply"));
        assertTrue(policy.allowed("/private/public/job"));
        assertTrue(policy.allowed("/jobs"));
    }
}
