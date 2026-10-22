package com.menix.jobassistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:smart-apply-tests;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Transactional
class SmartApplyMemoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SmartApplyAnswerRepository answers;
    @Autowired SmartApplyPreferencesRepository preferences;
    @PersistenceContext EntityManager entityManager;

    @BeforeEach
    void clearMemory() {
        answers.deleteAll();
        preferences.deleteAll();
    }

    @Test
    void approvesUpdatesTracksAndDeletesReusableAnswers() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "question", "What programming skills do you have?",
                "answer", "Java, SQL, and PostgreSQL",
                "category", "Skills",
                "source", "Manual"
        ));
        mvc.perform(post("/api/smart-apply/answers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approvedAt").exists())
                .andExpect(jsonPath("$.questionKey").value("what programming skills do you have"));
        mvc.perform(post("/api/smart-apply/answers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.answer").value("Java, SQL, and PostgreSQL"));
        mvc.perform(post("/api/smart-apply/answers").contentType(MediaType.APPLICATION_JSON).content(
                mapper.writeValueAsString(Map.of("question", "What programming skills do you have?", "answer", "Python"))
        )).andExpect(status().isConflict());

        Long id = answers.findAll().getFirst().getId();
        mvc.perform(put("/api/smart-apply/answers/" + id).contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(Map.of("answer", "Java, SQL, PostgreSQL, and Spring Boot", "excluded", true))
                )).andExpect(status().isOk())
                .andExpect(jsonPath("$.correctionCount").value(1))
                .andExpect(jsonPath("$.excluded").value(true));
        mvc.perform(post("/api/smart-apply/answers/" + id + "/used"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usageCount").value(1));
        mvc.perform(delete("/api/smart-apply/answers/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/smart-apply/answers")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/smart-apply/answers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/smart-apply/answers")).andExpect(status().isNoContent());
        mvc.perform(get("/api/smart-apply/answers")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void keepsConservativeReuseDefaultsAndValidatesConfidenceThresholds() throws Exception {
        mvc.perform(get("/api/smart-apply/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.automaticReuseApprovedAnswers").value(false))
                .andExpect(jsonPath("$.learningEnabled").value(true));
        mvc.perform(put("/api/smart-apply/preferences").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(Map.of(
                                "automaticReuseApprovedAnswers", true,
                                "learningEnabled", false,
                                "highConfidenceThreshold", 0.9,
                                "mediumConfidenceThreshold", 0.6
                        ))
                )).andExpect(status().isOk())
                .andExpect(jsonPath("$.automaticReuseApprovedAnswers").value(true))
                .andExpect(jsonPath("$.learningEnabled").value(false));
        mvc.perform(put("/api/smart-apply/preferences").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(Map.of("highConfidenceThreshold", 0.6, "mediumConfidenceThreshold", 0.8))
                )).andExpect(status().isBadRequest());
    }

    @Test
    void approvedAnswersSurviveEntityManagerReload() {
        SmartApplyAnswer answer = new SmartApplyAnswer();
        answer.setQuestion("What is your degree?");
        answer.setQuestionKey("what is your degree");
        answer.setAnswer("Bachelor of Science");
        answer.setCategory("Education");
        SmartApplyAnswer saved = answers.saveAndFlush(answer);
        entityManager.clear();
        SmartApplyAnswer restored = answers.findById(saved.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("Bachelor of Science", restored.getAnswer());
        org.junit.jupiter.api.Assertions.assertEquals("Education", restored.getCategory());
    }

    @Test
    void candidateMemoryFieldsAreAdditiveAndExplicitlySaved() throws Exception {
        mvc.perform(put("/api/profile").contentType(MediaType.APPLICATION_JSON).content(
                        mapper.writeValueAsString(Map.of(
                                "currentLocation", "Jaipur",
                                "degree", "Bachelor of Computer Applications",
                                "graduationYear", "2026",
                                "noticePeriod", "Immediate",
                                "workAuthorization", "User confirmed answer"
                        ))
                )).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentLocation").value("Jaipur"))
                .andExpect(jsonPath("$.degree").value("Bachelor of Computer Applications"))
                .andExpect(jsonPath("$.graduationYear").value("2026"))
                .andExpect(jsonPath("$.workAuthorization").value("User confirmed answer"));
    }
}
