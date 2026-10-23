package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

abstract class PublicFeedConnector implements JobSourceConnector {
    private final RestClient client;
    protected PublicFeedConnector() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(8));
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        client = RestClient.builder().requestFactory(factory).build();
    }

    protected JsonNode get(String url) {
        return client.get().uri(url).header("User-Agent", "Menix Job Assistant (local job discovery)").retrieve().body(JsonNode.class);
    }

    protected List<JsonNode> rows(JsonNode root, String key) {
        JsonNode data = root == null ? null : root.isArray() ? root : root.path(key);
        if (data == null || !data.isArray()) throw new IllegalStateException(sourceName() + " returned no job list.");
        List<JsonNode> result = new ArrayList<>();
        data.forEach(result::add);
        return result;
    }

    protected String text(JsonNode row, String key) {
        JsonNode value = row.path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    protected JobListing listing(JsonNode row, String title, String company, String location,
                                 String workMode, String description, String url, String applyUrl) {
        JobListing job = new JobListing();
        job.setSource(sourceName());
        job.setAttributionUrl(attributionUrl());
        job.setTitle(title);
        job.setCompany(company);
        job.setLocation(location);
        job.setWorkMode(workMode);
        job.setDescription(description);
        job.setUrl(url);
        job.setApplyUrl(applyUrl);
        JsonNode tags = row.path("tags");
        if (tags.isArray()) {
            List<String> values = new ArrayList<>();
            tags.forEach(tag -> values.add(tag.asText("")));
            job.setRequiredSkills(String.join(", ", values));
        }
        return job;
    }

    protected Instant instant(String value) {
        try { return Instant.parse(value); }
        catch (Exception ignored) {
            try { return LocalDate.parse(value.substring(0, 10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
            catch (Exception ignoredAgain) { return null; }
        }
    }

    protected Instant exact(String value) {
        return value.contains("T") ? instant(value) : null;
    }

    protected void published(JobListing job, String value) {
        Instant time = instant(value);
        if (time != null) {
            job.setPostedAt(time);
            job.setPublishedAt(time.atZone(java.time.ZoneOffset.UTC).toLocalDate());
            job.setExactPostingTime(value.contains("T"));
        }
    }
}

@Component
class GreenhouseJobSourceConnector extends PublicFeedConnector {
    private final String boards;
    GreenhouseJobSourceConnector(@Value("${app.greenhouse-boards:}") String boards) { this.boards = boards; }
    public String sourceName() { return "Greenhouse"; }
    public String attributionUrl() { return "https://boards.greenhouse.io/"; }
    public List<JsonNode> fetch() {
        if (boards.isBlank()) throw new IllegalStateException("Configure GREENHOUSE_BOARD_TOKENS first.");
        List<JsonNode> result = new ArrayList<>();
        for (String board : boards.split("[,;\\s]+")) {
            if (!board.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid Greenhouse board identifier.");
            String endpoint = "https://boards-api.greenhouse.io/v1/boards/" + board + "/jobs?content=true";
            JsonNode root = get(endpoint);
            for (JsonNode row : rows(root, "jobs")) {
                ObjectNode copy = row.deepCopy();
                copy.put("_menixBoard", board);
                copy.put("_menixAttribution", endpoint);
                result.add(copy);
            }
        }
        return result;
    }
    public JobListing normalize(JsonNode item) {
        String url = text(item, "absolute_url");
        JobListing job = listing(item, text(item, "title"), text(item, "_menixBoard"),
                item.path("location").path("name").asText(""), "", text(item, "content"), url, url);
        job.setSourceJobId(text(item, "id"));
        job.setAttributionUrl(text(item, "_menixAttribution"));
        return job;
    }
}

@Component
class LeverJobSourceConnector extends PublicFeedConnector {
    private final String sites;
    LeverJobSourceConnector(@Value("${app.lever-sites:}") String sites) { this.sites = sites; }
    public String sourceName() { return "Lever"; }
    public String attributionUrl() { return "https://jobs.lever.co/"; }
    public List<JsonNode> fetch() {
        if (sites.isBlank()) throw new IllegalStateException("Configure LEVER_SITE_NAMES first.");
        List<JsonNode> result = new ArrayList<>();
        for (String site : sites.split("[,;\\s]+")) {
            if (!site.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid Lever site identifier.");
            String endpoint = "https://api.lever.co/v0/postings/" + site + "?mode=json";
            JsonNode root = get(endpoint);
            for (JsonNode row : rows(root, "")) {
                ObjectNode copy = row.deepCopy();
                copy.put("_menixBoard", site);
                copy.put("_menixAttribution", endpoint);
                result.add(copy);
            }
        }
        return result;
    }
    public JobListing normalize(JsonNode item) {
        String url = text(item, "hostedUrl");
        JobListing job = listing(item, text(item, "text"), text(item, "_menixBoard"),
                item.path("categories").path("location").asText(""),
                item.path("categories").path("commitment").asText(""),
                text(item, "description"), url, text(item, "applyUrl"));
        job.setSourceJobId(text(item, "id"));
        job.setAttributionUrl(text(item, "_menixAttribution"));
        return job;
    }
    public Instant exactTimestamp(JsonNode item) {
        long createdAt = item.path("createdAt").asLong(0);
        return createdAt > 0 ? Instant.ofEpochMilli(createdAt) : null;
    }
}
