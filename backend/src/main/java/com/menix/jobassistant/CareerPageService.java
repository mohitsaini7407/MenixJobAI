package com.menix.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
class CareerPageService {
    static final String SOURCE = "Career pages";
    static final String SOURCE_TYPE = "CAREER_PAGE_JSONLD";
    static final String USER_AGENT = "MenixJobAI/1.0 (personal job tracker)";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final JobSourceConnectionRepository sources;
    private final JobRepository jobs;
    private final JobSourceSyncStateRepository syncStates;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final AtomicBoolean syncing = new AtomicBoolean();
    private long lastRequestNanos;

    CareerPageService(JobSourceConnectionRepository sources, JobRepository jobs,
                      JobSourceSyncStateRepository syncStates, ObjectMapper mapper) {
        this.sources = sources;
        this.jobs = jobs;
        this.syncStates = syncStates;
        this.mapper = mapper;
    }

    List<Map<String, Object>> list() {
        return sources.findAll().stream()
                .filter(source -> SOURCE_TYPE.equalsIgnoreCase(source.getSourceType()))
                .sorted(java.util.Comparator.comparing(JobSourceConnection::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(this::sourceMap).toList();
    }

    @Transactional
    Map<String, Object> add(String rawUrl) {
        String url = canonicalPublicHttps(rawUrl);
        if (sources.findBySourceUrl(url).isPresent())
            throw new IllegalArgumentException("This career page URL is already configured.");
        JobSourceConnection source = new JobSourceConnection();
        source.setName(URI.create(url).getHost());
        source.setSourceUrl(url);
        source.setSourceType(SOURCE_TYPE);
        source.setDetectedFormat("SCHEMA_JOBPOSTING_JSONLD");
        source.setConnectionStatus("NOT_TESTED");
        source.setTermsNotes("Public HTTPS page; only schema.org JobPosting JSON-LD is imported.");
        source.setRateLimitNotes("One page request per daily sync; robots.txt checked; one-second request spacing.");
        return sourceMap(sources.save(source));
    }

    @Transactional
    void remove(long id) {
        JobSourceConnection source = sources.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Career page source not found."));
        if (!SOURCE_TYPE.equalsIgnoreCase(source.getSourceType()))
            throw new IllegalArgumentException("The selected source is not a Career pages entry.");
        sources.delete(source);
    }

    Map<String, Object> status() {
        List<JobSourceConnection> pages = enabledPages();
        JobSourceSyncState state = syncStates.findById(SOURCE).orElse(null);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", SOURCE);
        status.put("mode", "Public schema.org JobPosting JSON-LD");
        status.put("configured", !pages.isEmpty());
        status.put("enabled", !pages.isEmpty());
        status.put("status", pages.isEmpty() ? "Not configured" : state == null ? "Ready"
                : state.getLatestError().isBlank() ? "Ready" : "Needs attention");
        status.put("configuredPages", pages.size());
        status.put("jobCount", jobs.findAll().stream()
                .filter(job -> SOURCE.equalsIgnoreCase(job.getSource())).count());
        if (state != null) {
            status.put("lastAttempt", Objects.toString(state.getLastAttemptAt(), ""));
            status.put("lastSuccessfulSync", Objects.toString(state.getLastSuccessfulSyncAt(), ""));
            status.put("fetched", Objects.requireNonNullElse(state.getFetched(), 0));
            status.put("imported", Objects.requireNonNullElse(state.getImported(), 0));
            status.put("updated", Objects.requireNonNullElse(state.getUpdated(), 0));
            status.put("rejected", Objects.requireNonNullElse(state.getRejected(), 0));
            status.put("deduplicated", Objects.requireNonNullElse(state.getDeduplicated(), 0));
            status.put("error", Objects.toString(state.getLatestError(), ""));
        } else {
            status.put("lastAttempt", "");
            status.put("lastSuccessfulSync", "");
            status.put("fetched", 0);
            status.put("imported", 0);
            status.put("updated", 0);
            status.put("rejected", 0);
            status.put("deduplicated", 0);
            status.put("error", "");
        }
        return status;
    }

    Map<String, Object> sync() {
        if (!syncing.compareAndSet(false, true))
            return Map.of("source", SOURCE, "imported", 0, "skipped", true, "message", "Career page sync is already running.");
        Instant attemptedAt = Instant.now();
        JobSourceSyncState state = syncStates.findById(SOURCE).orElseGet(() -> new JobSourceSyncState(SOURCE));
        state.setLastAttemptAt(attemptedAt);
        syncStates.save(state);
        int fetched = 0, imported = 0, updated = 0, rejected = 0, deduplicated = 0;
        int successfulPages = 0;
        List<String> errors = new ArrayList<>();
        Map<String, RobotsPolicy> robots = new HashMap<>();
        Set<String> seenListingUrls = new HashSet<>();
        Set<String> seenExternalIds = new HashSet<>();
        try {
            for (JobSourceConnection page : enabledPages()) {
                try {
                    FetchResult result = fetchPage(page.getSourceUrl(), robots);
                    if (!result.allowed()) {
                        note(page, "DISALLOWED_ROBOTS", result.message());
                        errors.add(page.getSourceUrl() + ": " + result.message());
                        continue;
                    }
                    if (result.status() < 200 || result.status() >= 300) {
                        throw new IllegalStateException("Career page returned HTTP " + result.status() + "; redirects are not followed.");
                    }
                    List<CareerPosting> postings = parsePostings(result.body(), page.getSourceUrl(), mapper);
                    if (postings.isEmpty()) {
                        note(page, "NO_STRUCTURED_DATA", "No structured job data found");
                        errors.add(page.getSourceUrl() + ": No structured job data found");
                        continue;
                    }
                    closeExpiredJobs(Instant.now(), page.getSourceUrl());
                    fetched += postings.size();
                    int pageImported = 0, pageUpdated = 0, pageDeduplicated = 0, pageRejected = 0;
                    for (CareerPosting posting : postings) {
                        try {
                            String externalIdKey = posting.identifier().isBlank() ? "" : posting.identifier().toLowerCase(Locale.ROOT);
                            if (!seenListingUrls.add(posting.url())
                                    || (!externalIdKey.isBlank() && !seenExternalIds.add(externalIdKey))) {
                                deduplicated++;
                                pageDeduplicated++;
                                continue;
                            }
                            JobListing existing = externalIdKey.isBlank() ? null
                                    : jobs.findFirstBySourceIgnoreCaseAndSourceJobIdIgnoreCase(SOURCE, posting.identifier()).orElse(null);
                            if (existing == null) existing = jobs.findByUrl(posting.url()).orElse(null);
                            JobListing job = existing == null ? new JobListing() : existing;
                            mapToJob(posting, job, page.getSourceUrl(), Instant.now());
                            jobs.save(job);
                            if (existing == null) {
                                imported++;
                                pageImported++;
                            } else {
                                updated++;
                                deduplicated++;
                                pageUpdated++;
                                pageDeduplicated++;
                            }
                        } catch (Exception mappingError) {
                            rejected++;
                            pageRejected++;
                        }
                    }
                    page.setLastSyncAt(LocalDateTime.now());
                    page.setLastSuccessfulSyncAt(Instant.now());
                    page.setLastFetched(postings.size());
                    page.setLastImported(pageImported);
                    page.setLastUpdated(pageUpdated);
                    page.setLastRejected(pageRejected);
                    page.setLastDeduplicated(pageDeduplicated);
                    page.setConnectionStatus("REACHABLE");
                    page.setLastError("");
                    sources.save(page);
                    successfulPages++;
                } catch (Exception error) {
                    note(page, "ERROR", safeError(error));
                    errors.add(page.getSourceUrl() + ": " + safeError(error));
                }
            }
            Instant finished = Instant.now();
            state.setFetched(fetched);
            state.setImported(imported);
            state.setUpdated(updated);
            state.setRejected(rejected);
            state.setDeduplicated(deduplicated);
            state.setLatestError(String.join("; ", errors));
            if (errors.isEmpty()) state.setLastSuccessfulSyncAt(finished);
            syncStates.save(state);
            return Map.of("source", SOURCE, "imported", imported, "updated", updated,
                    "rejected", rejected, "deduplicated", deduplicated, "fetched", fetched,
                    "successfulPages", successfulPages, "checkedAt", finished.toString(), "errors", errors);
        } finally {
            syncing.set(false);
        }
    }

    static List<CareerPosting> parsePostings(String html, String pageUrl, ObjectMapper mapper) {
        var document = Jsoup.parse(html);
        List<CareerPosting> postings = new ArrayList<>();
        for (var script : document.select("script[type]")) {
            if (!"application/ld+json".equalsIgnoreCase(script.attr("type").trim())) continue;
            try {
                JsonNode root = mapper.readTree(script.data().isBlank() ? script.html() : script.data());
                collectPostings(root, pageUrl, postings);
            } catch (Exception ignored) {
                // A malformed JSON-LD block does not suppress valid blocks elsewhere on the page.
            }
        }
        return postings;
    }

    private static void collectPostings(JsonNode node, String pageUrl, List<CareerPosting> output) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(child -> collectPostings(child, pageUrl, output));
            return;
        }
        if (!node.isObject()) return;
        JsonNode types = node.path("@type");
        boolean isPosting = isPostingType(types);
        if (isPosting) {
            CareerPosting posting = fromJsonLd(node, pageUrl);
            if (posting != null) output.add(posting);
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getValue().isContainerNode()) collectPostings(entry.getValue(), pageUrl, output);
        });
    }

    private static boolean isPostingType(JsonNode types) {
        if (types.isArray()) {
            for (JsonNode type : types) if (isPostingType(type)) return true;
            return false;
        }
        String value = text(types);
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('#'));
        return "JobPosting".equalsIgnoreCase(separator < 0 ? value : value.substring(separator + 1));
    }

    private static CareerPosting fromJsonLd(JsonNode node, String pageUrl) {
        String title = text(node.path("title"));
        if (title.isBlank()) return null;
        String company = text(node.path("hiringOrganization").path("name"));
        String location = locations(node.path("jobLocation"));
        String page = secureResolvedUrl(text(node.path("url")), pageUrl);
        if (page.isBlank()) return null;
        String applyUrl = secureResolvedUrl(text(node.path("applicationUrl")), page);
        if (applyUrl.isBlank()) applyUrl = page;
        String description = Jsoup.clean(text(node.path("description")), Safelist.none()).replaceAll("\\s+", " ").trim();
        String employmentType = text(node.path("employmentType"));
        String identifier = text(node.path("identifier").path("value"));
        String postedText = text(node.path("datePosted"));
        Instant postedAt = parseDateTime(postedText, false);
        Instant validThrough = parseDateTime(text(node.path("validThrough")), true);
        String workMode = text(node.path("jobLocationType")).toLowerCase(Locale.ROOT).contains("telecommute")
                || text(node.path("jobLocationType")).toLowerCase(Locale.ROOT).contains("remote") ? "Remote" : "";
        return new CareerPosting(title, company, location, description, page, applyUrl, employmentType,
                identifier, postedAt, !postedText.matches("^\\d{4}-\\d{2}-\\d{2}$") && postedAt != null,
                validThrough, workMode);
    }

    static void mapToJob(CareerPosting posting, JobListing job, String sourcePage, Instant observedAt) {
        boolean isNew = job.getId() == null;
        job.setTitle(posting.title());
        if (!posting.company().isBlank()) job.setCompany(posting.company());
        job.setLocation(posting.location());
        job.setDescription(posting.description().length() > 7900 ? posting.description().substring(0, 7900) : posting.description());
        job.setDescriptionStatus("full");
        job.setUrl(posting.url());
        job.setApplyUrl(posting.applyUrl());
        job.setApplicationUrlVerified(false);
        job.setSource(SOURCE);
        job.setSourceJobId(posting.identifier());
        job.setAttributionUrl(sourcePage);
        job.setEmploymentType(posting.employmentType());
        job.setWorkMode(posting.workMode());
        job.setPostedAt(posting.postedAt());
        job.setPublishedAt(posting.postedAt() == null ? null : posting.postedAt().atZone(ZoneOffset.UTC).toLocalDate());
        job.setExactPostingTime(posting.exactPostedAt());
        job.setValidThrough(posting.validThrough());
        job.setImportMethod("CAREER_PAGE_JSONLD");
        job.setRetrievedAt(LocalDateTime.now());
        job.setLastSeenAt(observedAt);
        job.setLastVerifiedAt(observedAt);
        job.setLastSuccessfulSyncAt(observedAt);
        job.setAvailabilityStatus(posting.validThrough() != null && posting.validThrough().isBefore(observedAt) ? "CLOSED" : "OPEN");
        job.setClosedAt("CLOSED".equals(job.getAvailabilityStatus()) ? observedAt : null);
        job.setFirstSeenAt(isNew ? observedAt : job.getFirstSeenAt());
        job.setLastUpdatedAt(LocalDateTime.now());
    }

    private void closeExpiredJobs(Instant now, String sourcePage) {
        for (JobListing job : jobs.findAll()) {
            if (SOURCE.equalsIgnoreCase(job.getSource()) && job.getValidThrough() != null
                    && sourcePage.equalsIgnoreCase(job.getAttributionUrl())
                    && job.getValidThrough().isBefore(now) && !"CLOSED".equalsIgnoreCase(job.getAvailabilityStatus())) {
                job.setAvailabilityStatus("CLOSED");
                job.setClosedAt(now);
                jobs.save(job);
            }
        }
    }

    private FetchResult fetchPage(String pageUrl, Map<String, RobotsPolicy> cachedPolicies) throws Exception {
        URI target = URI.create(canonicalPublicHttps(pageUrl));
        if ("/robots.txt".equalsIgnoreCase(target.getPath()))
            return new FetchResult(false, 0, "", "A robots.txt URL cannot be used as a career page.");
        String origin = target.getScheme() + "://" + target.getRawAuthority();
        RobotsPolicy policy = cachedPolicies.get(origin);
        if (policy == null) {
            try {
                FetchResult robotsResponse = request(origin + "/robots.txt");
                if (robotsResponse.status() == 404) policy = new RobotsPolicy(List.of(), List.of(), "");
                else if (robotsResponse.status() < 200 || robotsResponse.status() >= 300)
                    policy = new RobotsPolicy(List.of("/"), List.of(),
                            "robots.txt could not be checked (HTTP " + robotsResponse.status() + "); page skipped.");
                else policy = parseRobots(robotsResponse.body());
            } catch (Exception error) {
                policy = new RobotsPolicy(List.of("/"), List.of(),
                        "robots.txt could not be checked (" + safeError(error) + "); page skipped.");
            }
            cachedPolicies.put(origin, policy);
        }
        if (!policy.allowed(target.getRawPath().isBlank() ? "/" : target.getRawPath()))
            return new FetchResult(false, 0, "", policy.denialMessage().isBlank()
                    ? "Disallowed by robots.txt" : policy.denialMessage());
        FetchResult response = request(target.toString());
        return new FetchResult(true, response.status(), response.body(), "");
    }

    private synchronized FetchResult request(String url) throws Exception {
        long now = System.nanoTime();
        if (lastRequestNanos != 0) {
            long remaining = Duration.ofSeconds(1).toNanos() - (now - lastRequestNanos);
            if (remaining > 0) Thread.sleep(Math.max(1, (remaining + 999_999) / 1_000_000));
        }
        URI uri = URI.create(canonicalPublicHttps(url));
        SourceRegistryService.validatePublicHttps(uri.toString());
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", USER_AGENT).GET().build();
        lastRequestNanos = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("Response exceeded the 4 MB safety limit.");
            return new FetchResult(true, response.statusCode(), new String(bytes, StandardCharsets.UTF_8), "");
        }
    }

    static RobotsPolicy parseRobots(String body) {
        List<RobotsGroup> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<String> disallowed = new ArrayList<>(), allowed = new ArrayList<>();
        boolean sawDirective = false;
        for (String raw : body.split("\\R")) {
            String line = raw.split("#", 2)[0].trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ("user-agent".equals(key)) {
                if (sawDirective) {
                    groups.add(new RobotsGroup(List.copyOf(agents), List.copyOf(disallowed), List.copyOf(allowed)));
                    agents.clear();
                    disallowed.clear();
                    allowed.clear();
                    sawDirective = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
            } else if ("disallow".equals(key)) {
                sawDirective = true;
                if (!value.isBlank()) disallowed.add(value);
            } else if ("allow".equals(key)) {
                sawDirective = true;
                if (!value.isBlank()) allowed.add(value);
            }
        }
        if (!agents.isEmpty()) groups.add(new RobotsGroup(List.copyOf(agents), List.copyOf(disallowed), List.copyOf(allowed)));
        List<RobotsGroup> matching = groups.stream().filter(group -> group.agents().stream()
                .anyMatch(agent -> agent.contains("menixjobai"))).toList();
        if (matching.isEmpty()) matching = groups.stream().filter(group -> group.agents().contains("*")).toList();
        return new RobotsPolicy(matching.stream().flatMap(group -> group.disallowed().stream()).toList(),
                matching.stream().flatMap(group -> group.allowed().stream()).toList(), "");
    }

    private List<JobSourceConnection> enabledPages() {
        return sources.findAll().stream()
                .filter(source -> SOURCE_TYPE.equalsIgnoreCase(source.getSourceType()) && source.isEnabled())
                .toList();
    }

    private void note(JobSourceConnection page, String status, String message) {
        page.setConnectionStatus(status);
        page.setLastError(message);
        page.setLastSyncAt(LocalDateTime.now());
        page.setLastFetched(0);
        page.setLastImported(0);
        page.setLastUpdated(0);
        page.setLastRejected(0);
        page.setLastDeduplicated(0);
        sources.save(page);
    }

    private Map<String, Object> sourceMap(JobSourceConnection source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", source.getId());
        row.put("url", source.getSourceUrl());
        row.put("name", source.getName());
        row.put("enabled", source.isEnabled());
        row.put("status", source.getConnectionStatus());
        row.put("lastSyncAt", Objects.toString(source.getLastSyncAt(), ""));
        row.put("lastSuccessfulSync", Objects.toString(source.getLastSuccessfulSyncAt(), ""));
        row.put("fetched", Objects.requireNonNullElse(source.getLastFetched(), 0));
        row.put("imported", Objects.requireNonNullElse(source.getLastImported(), 0));
        row.put("updated", Objects.requireNonNullElse(source.getLastUpdated(), 0));
        row.put("rejected", Objects.requireNonNullElse(source.getLastRejected(), 0));
        row.put("deduplicated", Objects.requireNonNullElse(source.getLastDeduplicated(), 0));
        row.put("lastError", source.getLastError());
        return row;
    }

    private static String canonicalPublicHttps(String raw) {
        SourceRegistryService.validatePublicHttps(raw);
        URI uri = URI.create(raw.trim()).normalize();
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress())
                    throw new IllegalArgumentException("Private, local, or reserved network addresses are blocked.");
            }
        } catch (java.net.UnknownHostException error) {
            throw new IllegalArgumentException("The host could not be resolved. Check the URL and try again.");
        }
        try {
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort() == 443 ? -1 : uri.getPort(),
                    uri.getPath().isBlank() ? "/" : uri.getPath(), uri.getQuery(), null).toASCIIString();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Career page URL format is invalid.");
        }
    }

    private static String secureResolvedUrl(String raw, String base) {
        if (raw.isBlank()) return "";
        try {
            URI uri = URI.create(base).resolve(raw).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) return "";
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort() == 443 ? -1 : uri.getPort(), uri.getPath().isBlank() ? "/" : uri.getPath(),
                    uri.getQuery(), null).toASCIIString();
        } catch (Exception invalid) {
            return "";
        }
    }

    private static String locations(JsonNode raw) {
        List<String> values = new ArrayList<>();
        JsonNode locations = raw.isArray() ? raw : mapperArray(raw);
        for (JsonNode item : locations) {
            JsonNode address = item.path("address");
            if (address.isMissingNode()) address = item;
            String value = String.join(", ", List.of(text(address.path("streetAddress")), text(address.path("addressLocality")),
                    text(address.path("addressRegion")), country(address.path("addressCountry"))).stream()
                    .filter(part -> !part.isBlank()).toList());
            if (!value.isBlank()) values.add(value);
        }
        return String.join("; ", values);
    }

    private static JsonNode mapperArray(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode()
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add(value);
    }

    private static String country(JsonNode node) {
        return node.isObject() ? text(node.path("name")) : text(node);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(child -> {
                String value = text(child);
                if (!value.isBlank()) parts.add(value);
            });
            return String.join(", ", parts);
        }
        if (node.isObject()) return text(node.path("name"));
        return node.asText("").trim();
    }

    private static Instant parseDateTime(String value, boolean endOfDayForDateOnly) {
        if (value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception ignored) {}
        try { return java.time.Instant.parse(value); } catch (Exception ignored) {}
        try {
            LocalDate date = LocalDate.parse(value);
            return endOfDayForDateOnly ? date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1)
                    : date.atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeError(Exception error) {
        String message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    record CareerPosting(String title, String company, String location, String description, String url,
                         String applyUrl, String employmentType, String identifier, Instant postedAt,
                         boolean exactPostedAt, Instant validThrough, String workMode) {}
    private record FetchResult(boolean allowed, int status, String body, String message) {}
    record RobotsPolicy(List<String> disallowed, List<String> allowed, String denialMessage) {
        boolean allowed(String path) {
            int denyLength = disallowed.stream().filter(path::startsWith).mapToInt(String::length).max().orElse(-1);
            int allowLength = allowed.stream().filter(path::startsWith).mapToInt(String::length).max().orElse(-1);
            return allowLength >= denyLength;
        }
    }
    private record RobotsGroup(List<String> agents, List<String> disallowed, List<String> allowed) {}
}
