package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
class AdzunaSearchProfileService {
    private static final List<AdzunaSearchProfile> DEFAULT_PROFILES = List.of(
            new AdzunaSearchProfile("full stack developer", "Jaipur"),
            new AdzunaSearchProfile("software engineer", "remote"),
            new AdzunaSearchProfile("react developer", "India"));
    private final AdzunaSearchProfileRepository profiles;

    AdzunaSearchProfileService(AdzunaSearchProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Transactional
    public List<AdzunaSearchProfile> list() {
        List<AdzunaSearchProfile> saved = profiles.findAll();
        if (!saved.isEmpty()) return saved;
        return profiles.saveAll(DEFAULT_PROFILES.stream()
                .map(profile -> new AdzunaSearchProfile(profile.getWhat(), profile.getWhere())).toList());
    }

    public long count() {
        return profiles.count();
    }

    @Transactional
    public List<AdzunaSearchProfile> replace(List<Map<String, Object>> input) {
        if (input == null || input.isEmpty() || input.size() > 50) {
            throw new IllegalArgumentException("Provide between 1 and 50 Adzuna search profiles.");
        }
        List<AdzunaSearchProfile> replacement = new ArrayList<>();
        for (Map<String, Object> row : input) {
            String what = Objects.toString(row.get("what"), "").trim();
            String where = Objects.toString(row.get("where"), "").trim();
            if (what.isBlank() || where.isBlank() || what.length() > 200 || where.length() > 200) {
                throw new IllegalArgumentException("Each profile needs a keyword and location, up to 200 characters each.");
            }
            boolean duplicate = replacement.stream().anyMatch(profile ->
                    profile.getWhat().equalsIgnoreCase(what) && profile.getWhere().equalsIgnoreCase(where));
            if (!duplicate) replacement.add(new AdzunaSearchProfile(what, where));
        }
        profiles.deleteAllInBatch();
        return profiles.saveAll(replacement);
    }
}

@Service
class AdzunaDailyUsageService {
    private final AdzunaDailyApiUsageRepository usage;
    private final int dailyCap;

    AdzunaDailyUsageService(AdzunaDailyApiUsageRepository usage,
                            @Value("${app.adzuna-daily-call-cap:200}") int dailyCap) {
        this.usage = usage;
        this.dailyCap = Math.max(0, dailyCap);
    }

    @Transactional
    public synchronized boolean tryConsumeCall() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        AdzunaDailyApiUsage row = usage.findById(today).orElseGet(() -> new AdzunaDailyApiUsage(today));
        if (row.getCalls() >= dailyCap) return false;
        row.setCalls(row.getCalls() + 1);
        usage.save(row);
        return true;
    }

    public Map<String, Object> status() {
        int calls = usage.findById(LocalDate.now(ZoneOffset.UTC)).map(AdzunaDailyApiUsage::getCalls).orElse(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usageDate", LocalDate.now(ZoneOffset.UTC).toString());
        result.put("callsToday", calls);
        result.put("dailyCallCap", dailyCap);
        result.put("remainingCalls", Math.max(0, dailyCap - calls));
        return result;
    }
}

@Component
class AdzunaJobSourceConnector extends PublicFeedConnector {
    private static final int RESULTS_PER_PAGE = 50;
    private static final int MAX_PAGES_PER_PROFILE = 3;
    private final EmployerBoardHttpClient http;
    private final AdzunaSearchProfileService profiles;
    private final AdzunaDailyUsageService usage;
    private final String appId;
    private final String appKey;

    AdzunaJobSourceConnector(EmployerBoardHttpClient http, AdzunaSearchProfileService profiles,
                             AdzunaDailyUsageService usage,
                             @Value("${app.adzuna-app-id:}") String appId,
                             @Value("${app.adzuna-app-key:}") String appKey) {
        this.http = http;
        this.profiles = profiles;
        this.usage = usage;
        this.appId = appId == null ? "" : appId.trim();
        this.appKey = appKey == null ? "" : appKey.trim();
    }

    @Override public String sourceName() { return "Adzuna"; }
    @Override public String attributionUrl() { return "https://www.adzuna.in/"; }
    boolean apiConfigured() { return !appId.isBlank() && !appKey.isBlank(); }
    boolean enabledForSync() { return apiConfigured() && !profiles.list().isEmpty(); }
    Map<String, Object> usageStatus() { return usage.status(); }
    int profileCount() { return Math.toIntExact(profiles.count()); }

    @Override
    public List<JsonNode> fetch() throws Exception {
        if (!apiConfigured()) return List.of();
        List<AdzunaSearchProfile> configuredProfiles = profiles.list();
        List<JsonNode> jobs = new ArrayList<>();
        for (AdzunaSearchProfile profile : configuredProfiles) {
            for (int page = 1; page <= MAX_PAGES_PER_PROFILE; page++) {
                String endpoint = endpoint(page, profile);
                JsonNode response;
                try {
                    response = http.get("Adzuna", endpoint, usage::tryConsumeCall);
                } catch (EmployerBoardHttpClient.RequestQuotaExceededException capReached) {
                    return jobs;
                }
                JsonNode results = response.path("results");
                if (!results.isArray()) throw new IllegalStateException("Adzuna returned no job results.");
                for (JsonNode result : results) {
                    ObjectNode copy = result.deepCopy();
                    copy.put("_menixCity", city(result.path("location")));
                    jobs.add(copy);
                }
                if (results.size() < RESULTS_PER_PAGE) break;
            }
        }
        return jobs;
    }

    private String endpoint(int page, AdzunaSearchProfile profile) {
        return "https://api.adzuna.com/v1/api/jobs/in/search/" + page
                + "?app_id=" + encode(appId)
                + "&app_key=" + encode(appKey)
                + "&what=" + encode(profile.getWhat())
                + "&where=" + encode(profile.getWhere())
                + "&results_per_page=" + RESULTS_PER_PAGE
                + "&max_days_old=7&sort_by=date&content-type=application/json";
    }

    @Override
    public JobListing normalize(JsonNode item) {
        String redirectUrl = text(item, "redirect_url");
        String safeRedirect = isHttps(redirectUrl) ? redirectUrl : "";
        String title = text(item, "title");
        String company = text(item.path("company"), "display_name");
        String location = text(item.path("location"), "display_name");
        String description = EmployerBoardHtml.plainText(text(item, "description"));
        JobListing job = listing(item, title, company, location,
                location.toLowerCase(Locale.ROOT).contains("remote") ? "Remote" : "",
                description, safeRedirect, safeRedirect);
        job.setSourceJobId(text(item, "id"));
        job.setEmploymentType(EmployerBoardJson.joinNonBlank(
                text(item, "contract_time"), text(item, "contract_type")));
        job.setSalary(salary(item));
        published(job, text(item, "created"));
        return job;
    }

    @Override
    public Instant exactTimestamp(JsonNode item) {
        String value = text(item, "created");
        if (value.isBlank() || !value.contains("T")) return null;
        try { return Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(value)); }
        catch (Exception ignored) { return null; }
    }

    private static String salary(JsonNode item) {
        JsonNode minimum = item.path("salary_min");
        JsonNode maximum = item.path("salary_max");
        String min = minimum.isNumber() ? minimum.asText() : "";
        String max = maximum.isNumber() ? maximum.asText() : "";
        if (min.isBlank() && max.isBlank()) return "";
        String currency = EmployerBoardJson.text(item, "salary_currency");
        String range = min.isBlank() ? max : max.isBlank() ? min : min + " - " + max;
        return currency.isBlank() ? range : currency + " " + range;
    }

    private static String city(JsonNode location) {
        JsonNode areas = location.path("area");
        if (areas.isArray()) {
            for (JsonNode area : areas) {
                if (area.path("level").asInt(-1) == 3 && !EmployerBoardJson.text(area, "tag").isBlank()) {
                    return EmployerBoardJson.text(area, "tag");
                }
            }
        }
        String display = EmployerBoardJson.text(location, "display_name");
        int comma = display.indexOf(',');
        return comma < 0 ? display.trim() : display.substring(0, comma).trim();
    }

    private static boolean isHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && value.length() <= 2048;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
