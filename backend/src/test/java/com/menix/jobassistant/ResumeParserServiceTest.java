package com.menix.jobassistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumeParserServiceTest {
    @TempDir Path tempDir;

    @Test void extractsPlainTextForApplicationDrafts() throws Exception {
        Path resume = tempDir.resolve("candidate.txt");
        Files.writeString(resume, "Java developer\nBuilt a Spring Boot API.");
        assertEquals("Java developer\nBuilt a Spring Boot API.", new ResumeParserService().extractForDraft(resume));
    }

    @Test void rejectsUnsupportedSavedResumeFormat() throws Exception {
        Path resume = tempDir.resolve("candidate.exe");
        Files.writeString(resume, "not a resume");
        assertThrows(IllegalArgumentException.class, () -> new ResumeParserService().extractForDraft(resume));
    }
}
