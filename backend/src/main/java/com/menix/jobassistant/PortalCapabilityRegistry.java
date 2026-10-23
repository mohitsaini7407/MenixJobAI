package com.menix.jobassistant;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PortalCapabilityRegistry {
    private static final Set<String> EMPLOYER_APIS = Set.of("Greenhouse", "Lever", "Ashby", "Workable", "SmartRecruiters");
    public List<Map<String, Object>> list(Map<String, Object> sourceStatus) {
        Map<String, Map<String, Object>> actual = new LinkedHashMap<>();
        Object rows = sourceStatus.get("sources");
        if (rows instanceof List<?> sourceRows) {
            for (Object row : sourceRows) {
                if (row instanceof Map<?, ?> source) {
                    actual.put(String.valueOf(source.get("source")), cast(source));
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : List.of("Greenhouse", "Lever", "Ashby", "Workable", "SmartRecruiters", "Adzuna",
                "LinkedIn", "Naukri", "Indeed", "Google Jobs")) {
            boolean employerApi = EMPLOYER_APIS.contains(name);
            boolean adzunaApi = "Adzuna".equals(name);
            Map<String, Object> source = actual.getOrDefault(name, Map.of());
            boolean configured = Boolean.TRUE.equals(source.get("enabled"));
            boolean sourceConfigured = Boolean.TRUE.equals(source.get("configured"));
            Object lastSuccessfulSync = source.get("lastSuccessfulSync");
            boolean connected = lastSuccessfulSync instanceof String value && !value.isBlank();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("portal", name);
            row.put("providerType", employerApi ? "EMPLOYER_BOARD_API" : adzunaApi ? "JOB_SEARCH_API" : "EXTERNAL_SEARCH");
            row.put("badge", employerApi || adzunaApi ? "Direct API" : "External Link");
            row.put("jobSearch", employerApi || adzunaApi ? configured ? "SUPPORTED_API" : "UNAVAILABLE" : "EXTERNAL_LINK");
            row.put("jobDetails", employerApi || adzunaApi ? "API" : "EXTERNAL_LINK");
            row.put("enabled", configured);
            row.put("configured", sourceConfigured);
            row.put("accountConnection", "UNAVAILABLE");
            row.put("messagingRead", "UNAVAILABLE");
            row.put("messagingSend", "UNAVAILABLE");
            row.put("applicationSubmission", "EXTERNAL_MANUAL");
            row.put("connectionState", source.get("error") instanceof String error && !error.isBlank()
                    ? "ERROR" : (employerApi || adzunaApi) && configured ? connected ? "CONNECTED" : "NOT_TESTED" : "NOT_CONNECTED");
            row.put("lastSuccessfulSync", source.get("lastSuccessfulSync"));
            row.put("error", source.get("error"));
            for (String key : List.of("lastAttempt","fetched","imported","updated","rejected","deduplicated","verifiedJobs","verificationMaxAgeHours","status","apiCredentialsConfigured","profilesConfigured","usageDate","callsToday","dailyCallCap","remainingCalls")) {
                if (source.containsKey(key)) row.put(key, source.get(key));
            }
            row.put("limitations", limitation(name));
            result.add(row);
        }
        return result;
    }

    private static Map<String, Object> cast(Map<?, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        input.forEach((key, value) -> output.put(String.valueOf(key), value));
        return output;
    }

    private static String limitation(String portal) {
        if ("Adzuna".equals(portal)) return "Adzuna job-search API using saved search profiles; apply destinations are not followed during sync.";
        if (EMPLOYER_APIS.contains(portal)) return "Public employer board API only when board identifiers are configured; no account or submission integration.";
        return "Official external search link only. Messaging and application actions require manual interaction on the portal.";
    }
}
