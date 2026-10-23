package com.menix.jobassistant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
class DailySyncService {
    private final DailySyncSettingsRepository settingsRepository;
    private final JobSourceService sourceService;

    DailySyncService(DailySyncSettingsRepository settingsRepository, JobSourceService sourceService) {
        this.settingsRepository = settingsRepository;
        this.sourceService = sourceService;
    }

    @Transactional
    public DailySyncSettings settings() {
        return settingsRepository.findById(1L).orElseGet(() -> settingsRepository.save(new DailySyncSettings()));
    }

    @Transactional
    public DailySyncSettings update(Map<String, Object> body) {
        DailySyncSettings settings = settings();
        String time = Objects.toString(body.getOrDefault("syncTime", settings.getSyncTime()), "").trim();
        try {
            LocalTime.parse(time);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Choose a valid daily sync time.");
        }
        settings.setSyncTime(time);
        settings.setEnabled(bool(body, "enabled", settings.isEnabled()));
        settings.setRunOnOpen(bool(body, "runOnOpen", settings.isRunOnOpen()));
        settings.setCompanyBoardsEnabled(bool(body, "companyBoardsEnabled", settings.isCompanyBoardsEnabled()));
        settings.setAdzunaEnabled(bool(body, "adzunaEnabled", settings.isAdzunaEnabled()));
        settings.setEmailAlertsEnabled(bool(body, "emailAlertsEnabled", settings.isEmailAlertsEnabled()));
        settings.setCareerPagesEnabled(bool(body, "careerPagesEnabled", settings.isCareerPagesEnabled()));
        return settingsRepository.save(settings);
    }

    @Scheduled(fixedDelay = 60000)
    public void runDailyIfDue() {
        DailySyncSettings settings = settings();
        if (!settings.isEnabled() || Objects.equals(settings.getLastRunDate(), LocalDate.now())) return;
        if (LocalTime.now().isBefore(LocalTime.parse(settings.getSyncTime()))) return;
        runEnabledSources(settings, true);
    }

    @Transactional
    public Map<String, Object> runOnApplicationOpen() {
        DailySyncSettings settings = settings();
        if (!settings.isRunOnOpen()) return Map.of("skipped", true, "message", "Run on app open is disabled.");
        return runEnabledSources(settings, false);
    }

    private synchronized Map<String, Object> runEnabledSources(DailySyncSettings settings, boolean dailyRun) {
        List<String> selected = new ArrayList<>();
        if (settings.isCompanyBoardsEnabled()) selected.add("Company boards");
        if (settings.isAdzunaEnabled()) selected.add("Adzuna");
        if (settings.isEmailAlertsEnabled()) selected.add("Email alerts");
        if (settings.isCareerPagesEnabled()) selected.add("Career pages");
        if (selected.isEmpty()) {
            if (dailyRun) {
                settings.setLastRunDate(LocalDate.now());
                settingsRepository.save(settings);
            }
            return Map.of("skipped", true, "message", "No sources are enabled for automatic sync.");
        }

        int imported = 0, updated = 0, rejected = 0, deduplicated = 0;
        List<String> errors = new ArrayList<>();
        for (String source : selected) {
            Map<String, Object> result;
            try {
                result = sourceService.syncSource(source);
            } catch (RuntimeException error) {
                errors.add(source + ": " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
                continue;
            }
            imported += number(result.get("imported"));
            updated += number(result.get("updated"));
            rejected += number(result.get("rejected"));
            deduplicated += number(result.get("deduplicated"));
            String error = Objects.toString(result.get("error"), "");
            if (!error.isBlank()) errors.add(source + ": " + error);
            Object sourceErrors = result.get("errors");
            if (sourceErrors instanceof List<?> list) {
                list.stream().map(Objects::toString).filter(value -> !value.isBlank())
                        .forEach(value -> errors.add(source + ": " + value));
            }
        }
        Instant completedAt = Instant.now();
        settings.setLastRunAt(completedAt);
        settings.setLastError(String.join("; ", errors));
        if (dailyRun) settings.setLastRunDate(LocalDate.now());
        settingsRepository.save(settings);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("imported", imported);
        response.put("updated", updated);
        response.put("rejected", rejected);
        response.put("deduplicated", deduplicated);
        response.put("sourcesChecked", selected.size());
        response.put("errors", errors);
        response.put("lastRunAt", completedAt.toString());
        response.put("message", errors.isEmpty() ? "Automatic source sync completed." : "Automatic source sync completed with errors.");
        return response;
    }

    private boolean bool(Map<String, Object> body, String key, boolean fallback) {
        Object raw = body.get(key);
        return raw == null ? fallback : Boolean.parseBoolean(Objects.toString(raw));
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
