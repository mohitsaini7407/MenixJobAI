package com.menix.jobassistant;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PortalCapabilityRegistry {
    private static final Set<String> DIRECT_FEEDS = Set.of("Remotive", "Arbeitnow", "Jobicy");
    private static final Set<String> EMPLOYER_APIS = Set.of("Greenhouse", "Lever");
    private static final Set<String> SEARCH_LINKS = Set.of("LinkedIn", "Naukri", "Indeed", "Google Jobs");

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
        for (String name : List.of("Remotive", "Arbeitnow", "Jobicy", "Greenhouse", "Lever",
                "LinkedIn", "Naukri", "Indeed", "Google Jobs")) {
            boolean direct = DIRECT_FEEDS.contains(name);
            boolean employerApi = EMPLOYER_APIS.contains(name);
            boolean external = SEARCH_LINKS.contains(name);
            Map<String, Object> source = actual.getOrDefault(name, Map.of());
            boolean configured = Boolean.TRUE.equals(source.get("enabled"));
            boolean connected = source.get("lastSuccessfulSync") != null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("portal", name);
            row.put("providerType", direct ? "PUBLIC_API" : employerApi ? "EMPLOYER_BOARD_API" : "EXTERNAL_SEARCH");
            row.put("badge", direct || employerApi ? "Direct API" : "External Link");
            row.put("jobSearch", direct ? "SUPPORTED_API" : employerApi ? configured ? "SUPPORTED_API" : "UNAVAILABLE" : "EXTERNAL_LINK");
            row.put("jobDetails", direct || employerApi ? "API" : "EXTERNAL_LINK");
            row.put("accountConnection", "UNAVAILABLE");
            row.put("messagingRead", "UNAVAILABLE");
            row.put("messagingSend", "UNAVAILABLE");
            row.put("applicationSubmission", "EXTERNAL_MANUAL");
            row.put("connectionState", direct || configured ? connected ? "CONNECTED" : "NOT_TESTED" : "NOT_CONNECTED");
            row.put("lastSuccessfulSync", source.get("lastSuccessfulSync"));
            row.put("error", source.get("error"));
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
        if (DIRECT_FEEDS.contains(portal)) return "Public job feed only. No portal account, inbox, messaging, or application submission is connected.";
        if ("Greenhouse".equals(portal) || "Lever".equals(portal)) return "Public employer board API only when board identifiers are configured; no account or submission integration.";
        return "Official external search link only. Messaging and application actions require manual interaction on the portal.";
    }
}
