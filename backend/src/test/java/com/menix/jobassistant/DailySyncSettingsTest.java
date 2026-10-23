package com.menix.jobassistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:menix-daily-sync;DB_CLOSE_DELAY=-1",
        "app.public-job-feeds-enabled=false"
})
@AutoConfigureMockMvc
class DailySyncSettingsTest {
    @Autowired private MockMvc mvc;
    @Autowired private DailySyncSettingsRepository settings;
    @Autowired private JobRepository jobs;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach void clearSettings() { settings.deleteAll(); }

    @Test void syncSettingsArePersistedAndValidated() throws Exception {
        mvc.perform(get("/api/settings/automatic-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.syncTime").value("08:15"));

        mvc.perform(put("/api/settings/automatic-sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"syncTime\":\"09:30\",\"runOnOpen\":true,\"companyBoardsEnabled\":false,\"adzunaEnabled\":true,\"emailAlertsEnabled\":false,\"careerPagesEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.syncTime").value("09:30"))
                .andExpect(jsonPath("$.runOnOpen").value(true))
                .andExpect(jsonPath("$.companyBoardsEnabled").value(false))
                .andExpect(jsonPath("$.adzunaEnabled").value(true))
                .andExpect(jsonPath("$.emailAlertsEnabled").value(false))
                .andExpect(jsonPath("$.careerPagesEnabled").value(true));

        mvc.perform(get("/api/settings/automatic-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.syncTime").value("09:30"));
        mvc.perform(put("/api/settings/automatic-sync").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncTime\":\"25:61\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void appOpenSyncIsSkippedUnlessExplicitlyEnabled() throws Exception {
        mvc.perform(post("/api/jobs/sync/automatic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(true));
    }

    @Test void dashboardJobEndpointsWorkAfterSchemaUpdate() throws Exception {
        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0").param("activeOnly", "false"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/jobs/diagnostics"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sources"))
                .andExpect(status().isOk());
    }

    @Test void legacyJobsWithNoDescriptionStatusRemainReadable() throws Exception {
        JobListing job = new JobListing();
        job.setTitle("Legacy listing");
        job.setCompany("Example");
        job.setUrl("https://jobs.example.test/legacy-listing");
        job = jobs.save(job);
        jdbc.update("UPDATE job_listing SET description_status = NULL WHERE id = ?", job.getId());

        mvc.perform(get("/api/jobs").param("freshness", "all").param("minScore", "0").param("activeOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].descriptionStatus").value("unknown"));
    }
}
