package com.menix.jobassistant;

import static com.menix.jobassistant.EmployerBoardJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

@Component
class EmployerBoardHttpClient {
    private static final String USER_AGENT = "MenixJobAI/1.0 (personal job tracker)";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final Map<String, Long> nextAllowedRequestNanos = new LinkedHashMap<>();

    EmployerBoardHttpClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode get(String provider, String endpoint) throws Exception {
        return get(provider, endpoint, () -> true);
    }

    JsonNode get(String provider, String endpoint, BooleanSupplier allowRequest) throws Exception {
        SourceRegistryService.validatePublicHttps(endpoint);
        for (int rateLimitRetries = 0, serverRetries = 0; ; ) {
            throttle(provider);
            if (!allowRequest.getAsBoolean()) throw new RequestQuotaExceededException(provider);
            URLConnection opened = URI.create(endpoint).toURL().openConnection();
            if (!(opened instanceof HttpURLConnection connection)) {
                throw new IllegalArgumentException("Employer board endpoints must use HTTPS.");
            }
            connection.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            try {
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    try (var stream = connection.getInputStream()) {
                        return mapper.readTree(stream.readNBytes(MAX_RESPONSE_BYTES));
                    }
                }
                if (status == 429 && rateLimitRetries < 3) {
                    long waitMillis = retryAfterMillis(connection.getHeaderField("Retry-After"), rateLimitRetries);
                    rateLimitRetries++;
                    connection.disconnect();
                    Thread.sleep(waitMillis);
                    continue;
                }
                if (status >= 500 && status <= 599 && serverRetries == 0) {
                    serverRetries++;
                    connection.disconnect();
                    Thread.sleep(1000);
                    continue;
                }
                throw new IllegalStateException("HTTP " + status + " from " + provider + "; redirects are not followed.");
            } finally {
                connection.disconnect();
            }
        }
    }

    static final class RequestQuotaExceededException extends RuntimeException {
        RequestQuotaExceededException(String provider) {
            super(provider + " daily API request cap reached.");
        }
    }

    private void throttle(String provider) throws InterruptedException {
        long now = System.nanoTime();
        long remaining;
        synchronized (this) {
            long earliest = Math.max(now, nextAllowedRequestNanos.getOrDefault(provider, now));
            remaining = earliest - now;
            nextAllowedRequestNanos.put(provider, earliest + Duration.ofSeconds(1).toNanos());
        }
        if (remaining > 0) {
            Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        }
    }

    private long retryAfterMillis(String value, int attempt) {
        if (value != null) {
            try {
                return Math.max(1000, Math.min(120_000, Long.parseLong(value.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                try {
                    long delay = Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                    return Math.max(1000, Math.min(120_000, delay));
                } catch (Exception ignoredDate) {
                    // Fall through to bounded exponential backoff for an invalid Retry-After value.
                }
            }
        }
        return 1000L << attempt;
    }
}

interface CompanyBoardProviderConnector extends JobSourceConnector {
    String providerKey();
    List<JsonNode> fetchBoard(String identifier) throws Exception;
    int testBoard(String identifier) throws Exception;
}

abstract class ConfiguredEmployerBoardConnector extends PublicFeedConnector implements CompanyBoardProviderConnector {
    private final CompanyBoardConfigurationRepository boards;
    private final EmployerBoardHttpClient http;
    private final String environmentIdentifiers;
    private final String configurationName;

    ConfiguredEmployerBoardConnector(CompanyBoardConfigurationRepository boards, EmployerBoardHttpClient http,
                                     String environmentIdentifiers, String configurationName) {
        this.boards = boards;
        this.http = http;
        this.environmentIdentifiers = environmentIdentifiers == null ? "" : environmentIdentifiers;
        this.configurationName = configurationName;
    }

    protected abstract JsonNode request(String identifier, int offset, int limit) throws Exception;
    protected abstract List<JsonNode> responseRows(JsonNode root);
    protected boolean paginated() { return false; }
    protected int totalFound(JsonNode root) { return -1; }
    protected String companyName(JsonNode root, String identifier) { return ""; }
    protected String endpoint(String identifier, int offset, int limit) { throw new UnsupportedOperationException(); }

    @Override
    public List<JsonNode> fetch() throws Exception {
        List<BoardReference> configured = configuredBoards();
        if (configured.isEmpty()) {
            throw new IllegalStateException("Configure " + configurationName + " or add a company board in Sources.");
        }
        List<JsonNode> result = new ArrayList<>();
        for (BoardReference board : configured) {
            validateIdentifier(board.identifier());
            List<JsonNode> rows = fetchBoard(board.identifier());
            for (JsonNode row : rows) {
                ObjectNode copy = row.deepCopy();
                copy.put("_menixBoard", board.identifier());
                if (!board.company().isBlank()) copy.put("_menixCompany", board.company());
                copy.put("_menixAttribution", endpoint(board.identifier(), 0, 100));
                result.add(copy);
            }
        }
        return result;
    }

    @Override
    public List<JsonNode> fetchBoard(String identifier) throws Exception {
        validateIdentifier(identifier);
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < 100; page++) {
            JsonNode root = request(identifier, offset, 100);
            List<JsonNode> pageRows = responseRows(root);
            String company = companyName(root, identifier);
            for (int index = 0; index < pageRows.size(); index++) {
                ObjectNode copy = pageRows.get(index).deepCopy();
                copy.put("_menixCompany", company);
                pageRows.set(index, copy);
            }
            if (pageRows.size() > 10_000 - all.size()) {
                throw new IllegalStateException(sourceName() + " board exceeds the 10,000-job per-board safety limit.");
            }
            all.addAll(pageRows);
            if (!paginated() || pageRows.isEmpty()) break;
            int total = totalFound(root);
            offset += pageRows.size();
            if (total >= 0 ? offset >= total : pageRows.size() < 100) break;
            if (page == 99) throw new IllegalStateException(sourceName() + " pagination exceeded 100 pages.");
        }
        return all;
    }

    @Override
    public int testBoard(String identifier) throws Exception {
        validateIdentifier(identifier);
        JsonNode root = request(identifier, 0, 100);
        List<JsonNode> rows = responseRows(root);
        int total = totalFound(root);
        return total >= 0 ? total : rows.size();
    }

    protected JsonNode getBoardJson(String endpoint) throws Exception {
        return http.get(providerKey(), endpoint);
    }

    private List<BoardReference> configuredBoards() {
        Map<String, BoardReference> result = new LinkedHashMap<>();
        for (String token : environmentIdentifiers.split("[,;\\s]+")) {
            String identifier = token.trim();
            if (!identifier.isBlank()) result.putIfAbsent(identifier.toLowerCase(Locale.ROOT), new BoardReference(identifier, ""));
        }
        for (CompanyBoardConfiguration board : boards.findByProviderIgnoreCaseAndEnabledTrue(providerKey())) {
            result.put(board.getIdentifier().toLowerCase(Locale.ROOT), new BoardReference(board.getIdentifier(), board.getCompany()));
        }
        return List.copyOf(result.values());
    }

    protected void validateIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Invalid " + sourceName() + " board identifier.");
        }
    }

    private record BoardReference(String identifier, String company) {}
}

@Component
class AshbyJobSourceConnector extends ConfiguredEmployerBoardConnector {
    AshbyJobSourceConnector(CompanyBoardConfigurationRepository boards, EmployerBoardHttpClient http,
                            @Value("${app.ashby-board-names:}") String names) {
        super(boards, http, names, "ASHBY_BOARD_NAMES");
    }
    public String sourceName() { return "Ashby"; }
    public String providerKey() { return "ASHBY"; }
    public String attributionUrl() { return "https://api.ashbyhq.com/posting-api/job-board/"; }
    protected String endpoint(String board, int offset, int limit) { return attributionUrl() + board; }
    protected JsonNode request(String board, int offset, int limit) throws Exception { return getBoardJson(endpoint(board, offset, limit)); }
    protected List<JsonNode> responseRows(JsonNode root) { return array(root, "jobs", sourceName()); }
    protected String companyName(JsonNode root, String board) { return text(root, "organizationName"); }
    public JobListing normalize(JsonNode item) {
        String listingUrl = firstHttps(item, "jobUrl", "url");
        String applyUrl = firstHttps(item, "applyUrl", "applicationUrl");
        String description = first(item, "descriptionPlain", "descriptionHtml", "description");
        String location = locationText(item.path("location"));
        String workMode = truthy(item.path("isRemote")) || location.toLowerCase(Locale.ROOT).contains("remote") ? "Remote" : "";
        JobListing job = listing(item, text(item, "title"), first(item, "_menixCompany", "organizationName"),
                location, workMode, EmployerBoardHtml.plainText(description), listingUrl,
                applyUrl.isBlank() ? listingUrl : applyUrl);
        job.setSourceJobId(first(item, "id", "jobId"));
        job.setEmploymentType(text(item, "employmentType"));
        job.setExperienceRequirements(first(item, "experienceRequirements", "seniority"));
        job.setSalary(first(item, "salary", "compensationTierSummary"));
        published(job, text(item, "publishedAt"));
        return job;
    }
    public Instant exactTimestamp(JsonNode item) { return EmployerBoardJson.exactTimestamp(item, "publishedAt"); }
}

@Component
class WorkableJobSourceConnector extends ConfiguredEmployerBoardConnector {
    WorkableJobSourceConnector(CompanyBoardConfigurationRepository boards, EmployerBoardHttpClient http,
                              @Value("${app.workable-accounts:}") String accounts) {
        super(boards, http, accounts, "WORKABLE_ACCOUNTS");
    }
    public String sourceName() { return "Workable"; }
    public String providerKey() { return "WORKABLE"; }
    public String attributionUrl() { return "https://apply.workable.com/api/v1/widget/accounts/"; }
    protected String endpoint(String account, int offset, int limit) { return attributionUrl() + account; }
    protected JsonNode request(String account, int offset, int limit) throws Exception { return getBoardJson(endpoint(account, offset, limit)); }
    protected List<JsonNode> responseRows(JsonNode root) { return array(root, "jobs", sourceName()); }
    protected String companyName(JsonNode root, String account) { return first(root, "name", "company"); }
    public JobListing normalize(JsonNode item) {
        String listingUrl = firstHttps(item, "url", "shortlink", "short_link", "application_url");
        String applyUrl = firstHttps(item, "application_url", "apply_url");
        String location = locationText(item.path("location"));
        boolean remote = truthy(item.path("remote")) || location.toLowerCase(Locale.ROOT).contains("remote");
        String description = joinNonBlank(text(item, "description"), text(item, "requirements"), text(item, "benefits"));
        JobListing job = listing(item, first(item, "title", "full_title"),
                first(item, "_menixCompany", "company_name", "company"), location,
                remote ? "Remote" : text(item, "workplace"), EmployerBoardHtml.plainText(description),
                listingUrl, applyUrl.isBlank() ? listingUrl : applyUrl);
        job.setSourceJobId(first(item, "shortcode", "id"));
        job.setEmploymentType(first(item, "employment_type", "employmentType"));
        job.setSalary(first(item, "salary", "salary_range"));
        job.setExperienceRequirements(first(item, "experience", "experience_requirements", "seniority"));
        published(job, first(item, "created_at", "createdAt"));
        return job;
    }
    public Instant exactTimestamp(JsonNode item) { return EmployerBoardJson.exactTimestamp(item, "created_at", "createdAt"); }
}

@Component
class SmartRecruitersJobSourceConnector extends ConfiguredEmployerBoardConnector {
    SmartRecruitersJobSourceConnector(CompanyBoardConfigurationRepository boards, EmployerBoardHttpClient http,
                                     @Value("${app.smartrecruiters-companies:}") String companies) {
        super(boards, http, companies, "SMARTRECRUITERS_COMPANIES");
    }
    public String sourceName() { return "SmartRecruiters"; }
    public String providerKey() { return "SMARTRECRUITERS"; }
    public String attributionUrl() { return "https://api.smartrecruiters.com/v1/companies/"; }
    protected String endpoint(String company, int offset, int limit) {
        return attributionUrl() + company + "/postings?offset=" + offset + "&limit=" + limit;
    }
    protected JsonNode request(String company, int offset, int limit) throws Exception { return getBoardJson(endpoint(company, offset, limit)); }
    protected List<JsonNode> responseRows(JsonNode root) { return array(root, "content", sourceName()); }
    protected boolean paginated() { return true; }
    protected int totalFound(JsonNode root) { return root.path("totalFound").asInt(-1); }
    protected String companyName(JsonNode root, String company) {
        List<JsonNode> rows = responseRows(root);
        return rows.isEmpty() ? "" : text(rows.get(0).path("company"), "name");
    }
    public JobListing normalize(JsonNode item) {
        String listingUrl = firstHttps(item, "ref", "url");
        String applyUrl = firstHttps(item, "applyUrl", "applicationUrl");
        if (applyUrl.isBlank()) applyUrl = listingUrl;
        JsonNode locationNode = item.path("location");
        String location = locationText(locationNode);
        boolean remote = truthy(locationNode.path("remote")) || location.toLowerCase(Locale.ROOT).contains("remote");
        String description = smartRecruitersDescription(item.path("jobAd"));
        String company = first(item.path("company"), "name");
        if (company.isBlank()) company = text(item, "_menixCompany");
        JobListing job = listing(item, text(item, "name"), company, location, remote ? "Remote" : "",
                EmployerBoardHtml.plainText(description), listingUrl, applyUrl);
        job.setSourceJobId(first(item, "id", "uuid"));
        job.setEmploymentType(text(item.path("typeOfEmployment"), "label"));
        job.setExperienceRequirements(first(item.path("experienceLevel"), "label", "name"));
        job.setSalary(smartRecruitersSalary(item.path("salary")));
        published(job, text(item, "releasedDate"));
        return job;
    }
    public Instant exactTimestamp(JsonNode item) { return EmployerBoardJson.exactTimestamp(item, "releasedDate"); }

    private static String smartRecruitersDescription(JsonNode jobAd) {
        JsonNode sections = jobAd.path("sections");
        if (!sections.isObject()) return first(jobAd, "description", "text");
        List<String> content = new ArrayList<>();
        sections.fields().forEachRemaining(entry -> {
            String text = first(entry.getValue(), "text", "content");
            if (!text.isBlank()) content.add(text);
        });
        return String.join("\n", content);
    }

    private static String smartRecruitersSalary(JsonNode salary) {
        if (salary.isMissingNode() || salary.isNull()) return "";
        String label = first(salary, "label");
        if (!label.isBlank()) return label;
        String minimum = EmployerBoardJson.text(salary, "min");
        String maximum = EmployerBoardJson.text(salary, "max");
        String currency = first(salary, "currency", "currencyCode");
        String range = minimum.isBlank() ? maximum : maximum.isBlank() ? minimum : minimum + "–" + maximum;
        return range.isBlank() ? "" : currency.isBlank() ? range : range + " " + currency;
    }
}

final class EmployerBoardHtml {
    private EmployerBoardHtml() {}

    static String plainText(String html) {
        if (html == null || html.isBlank()) return "";
        StringBuilder text = new StringBuilder();
        final int[] ignoredDepth = {0};
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                @Override
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) ignoredDepth[0]++;
                    else if (ignoredDepth[0] == 0) text.append(' ');
                }
                @Override
                public void handleEndTag(HTML.Tag tag, int position) {
                    if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && ignoredDepth[0] > 0) ignoredDepth[0]--;
                    else if (ignoredDepth[0] == 0) text.append(' ');
                }
                @Override
                public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    if (ignoredDepth[0] == 0) text.append(' ');
                }
                @Override
                public void handleText(char[] data, int position) {
                    if (ignoredDepth[0] == 0) text.append(data).append(' ');
                }
            }, true);
        } catch (Exception ignored) {
            return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }
}

final class EmployerBoardJson {
    private EmployerBoardJson() {}

    static List<JsonNode> array(JsonNode root, String key, String source) {
        JsonNode value = root == null ? null : root.path(key);
        if (value == null || !value.isArray()) throw new IllegalStateException(source + " returned no job list.");
        List<JsonNode> rows = new ArrayList<>();
        value.forEach(rows::add);
        return rows;
    }

    static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.path(key);
        return value == null || value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    static String first(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static String locationText(JsonNode location) {
        if (location == null || location.isMissingNode() || location.isNull()) return "";
        if (location.isTextual()) return location.asText();
        List<String> parts = new ArrayList<>();
        for (String key : List.of("name", "city", "region", "country")) {
            String value = text(location, key);
            if (!value.isBlank() && !parts.contains(value)) parts.add(value);
        }
        return String.join(", ", parts);
    }

    static boolean truthy(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return false;
        String text = value.asText("").toLowerCase(Locale.ROOT);
        return value.asBoolean(false) || text.equals("true") || text.equals("remote");
    }

    static String joinNonBlank(String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .distinct().reduce((first, next) -> first + "\n" + next).orElse("");
    }

    static String httpsUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(raw.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? uri.toASCIIString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    static String firstHttps(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = httpsUrl(text(node, key));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static Instant timestamp(JsonNode item, String... keys) {
        String value = first(item, keys);
        if (value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (Exception ignored) {
            try { return java.time.LocalDate.parse(value.substring(0, 10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(); }
            catch (Exception ignoredDate) { return null; }
        }
    }

    static Instant exactTimestamp(JsonNode item, String... keys) {
        String value = first(item, keys);
        return value.contains("T") ? timestamp(item, keys) : null;
    }
}
