package com.menix.jobassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiMatchServiceTest {
    private static final String RESULT = "{\"matchScore\":88,\"matchedSkills\":[\"Java\",\"SQL\"],\"missingRequiredSkills\":[\"Kubernetes\"],\"preferredSkillMatches\":[\"Spring Boot\"],\"seniorityCompatibility\":\"MATCH\",\"geographicEligibility\":\"ELIGIBLE\",\"workAuthorizationUncertainty\":\"unknown\",\"reasonsForMatch\":[\"Entry level role\"],\"reasonsForMismatch\":[],\"recommendation\":\"REVIEW\",\"warnings\":[]}";
    private static final String WRAPPED = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(RESULT) + "}]}}]}";
    private static final String DRAFT = "{\"answers\":[{\"id\":\"f0\",\"value\":\"Built a REST API\\n• Added validation\"},{\"id\":\"f1\",\"value\":\"remote\"}]}";
    private static final String WRAPPED_DRAFT = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(DRAFT) + "}]}}]}";

    private GeminiMatchService service(MockRestServiceServer server, RestClient.Builder builder) {
        return new GeminiMatchService(builder.build(), new ObjectMapper(), "test-key-only", "gemini-test", 0);
    }
    private static String quote(String text) {
        try { return new ObjectMapper().writeValueAsString(text); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private CandidateProfile profile() { CandidateProfile p=new CandidateProfile();p.setFullName("PRIVATE CANDIDATE NAME");p.setEmail("private@example.invalid");p.setExperienceLevel("Fresher / entry level");p.setSkills("Java, SQL");p.setPreferredTitles("Junior Java Developer");p.setPreferredLocations("India, Remote");return p; }
    private JobListing job() { JobListing j=new JobListing();j.setId(17L);j.setTitle("Junior Java Developer");j.setCompany("Example Co");j.setLocation("Remote - India");j.setRequiredSkills("Java, SQL, Kubernetes");j.setDescription("A junior role using Java and Spring Boot.");j.setPostedAt(Instant.now());return j; }
    private java.util.List<Map<String,Object>> formFields() {
        return java.util.List.of(
                Map.of("id","f0","type","textarea","label","Describe a relevant project","name","projects","context","Use bullet points where useful","placeholder","","required",true,"options",java.util.List.of()),
                Map.of("id","f1","type","select","label","Preferred work mode","name","workMode","context","","placeholder","","required",false,"options",java.util.List.of(Map.of("value","remote","label","Remote"),Map.of("value","hybrid","label","Hybrid"))));
    }

    @Test void parsesStrictStructuredOutputCachesByProfileAndOmitsPersonalIdentifiers() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-goog-api-key","test-key-only"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE CANDIDATE NAME"))))
                .andRespond(withSuccess(WRAPPED,MediaType.APPLICATION_JSON));
        GeminiMatchService service=service(server,builder);JobListing j=job();CandidateProfile p=profile();
        Map<String,Object> first=service.analyze(j,p);
        assertEquals(88,first.get("aiScore"));assertEquals("Not assessed; work authorization has not been provided in the candidate profile.",first.get("workAuthorizationUncertainty"));assertEquals(false,first.get("cached"));
        Map<String,Object> cached=service.analyze(j,p);assertEquals(true,cached.get("cached"));server.verify();
    }

    @Test void rejectsMalformedScoresAndDoesNotCacheThem() {
        String invalid=RESULT.replace("\"matchScore\":88","\"matchScore\":188");
        String body="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"+quote(invalid)+"}]}}]}";
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        GeminiMatchService service=service(server,builder);
        assertThrows(ResponseStatusException.class,()->service.analyze(job(),profile()));server.verify();
    }

    @Test void mapsQuotaErrorsToRateLimitResponse() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("{}"));
        GeminiMatchService service=service(server,builder);
        ResponseStatusException error=assertThrows(ResponseStatusException.class,()->service.analyze(job(),profile()));
        assertEquals(429,error.getStatusCode().value());server.verify();
    }

    @Test void draftsStrictApplicationAnswersFromResumeAndJobWithoutCaching() {
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A resume fact: built a REST API")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Junior Java Developer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Describe a relevant project")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE CANDIDATE NAME"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private@example.invalid"))))
                .andRespond(withSuccess(WRAPPED_DRAFT,MediaType.APPLICATION_JSON));
        GeminiMatchService service=service(server,builder);
        java.util.List<Map<String,String>> result=service.draftApplication(job(),profile(),"A resume fact: built a REST API",formFields());
        assertEquals("Built a REST API\n• Added validation",result.get(0).get("value"));
        assertEquals("remote",result.get(1).get("value"));
        assertEquals(2,result.size());
        server.verify();
    }

    @Test void rejectsUnexpectedApplicationDraftFields() {
        String invalid="{\"answers\":[{\"id\":\"f0\",\"value\":\"valid\"},{\"id\":\"f1\",\"value\":\"not-an-option\"}]}";
        String body="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"+quote(invalid)+"}]}}]}";
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        GeminiMatchService service=service(server,builder);
        assertThrows(ResponseStatusException.class,()->service.draftApplication(job(),profile(),"Resume text",formFields()));
        server.verify();
    }

    @Test void rejectsIncompleteFormAnswerSets() {
        String incomplete="{\"answers\":[{\"id\":\"f0\",\"value\":\"answer\"}]}";
        String body="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"+quote(incomplete)+"}]}}]}";
        RestClient.Builder builder=RestClient.builder();MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        GeminiMatchService service=service(server,builder);
        assertThrows(ResponseStatusException.class,()->service.draftApplication(job(),profile(),"Resume text",formFields()));
        server.verify();
    }
}
