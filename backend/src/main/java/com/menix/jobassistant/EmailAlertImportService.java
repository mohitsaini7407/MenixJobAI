package com.menix.jobassistant;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@Service
class EmailAlertImportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailAlertImportService.class);
    private final EmailAlertSettingsService settingsService;
    private final EmailAlertSettingsRepository settingsRepository;
    private final EmailAlertProcessedMessageRepository processedMessages;
    private final JobRepository jobs;
    private final Map<String, AlertEmailParser> parsersByProvider;
    private final java.util.concurrent.atomic.AtomicBoolean importing = new java.util.concurrent.atomic.AtomicBoolean();

    EmailAlertImportService(EmailAlertSettingsService settingsService, EmailAlertSettingsRepository settingsRepository,
                            EmailAlertProcessedMessageRepository processedMessages, JobRepository jobs,
                            List<AlertEmailParser> parsers) {
        this.settingsService = settingsService;
        this.settingsRepository = settingsRepository;
        this.processedMessages = processedMessages;
        this.jobs = jobs;
        this.parsersByProvider = Map.of("Naukri", parsers.stream().filter(NaukriAlertEmailParser.class::isInstance).findFirst().orElseThrow(),
                "LinkedIn", parsers.stream().filter(LinkedInAlertEmailParser.class::isInstance).findFirst().orElseThrow(),
                "Indeed", parsers.stream().filter(IndeedAlertEmailParser.class::isInstance).findFirst().orElseThrow(),
                "Glassdoor", parsers.stream().filter(GlassdoorAlertEmailParser.class::isInstance).findFirst().orElseThrow());
    }

    public Map<String, Object> status() {
        Map<String, Object> configured = settingsService.publicSettings();
        boolean enabled = Boolean.TRUE.equals(configured.get("enabled"));
        boolean ready = Boolean.TRUE.equals(configured.get("hasAppPassword"))
                && !Objects.toString(configured.get("emailAddress"), "").isBlank();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "Email alerts");
        result.put("mode", "User-authorized IMAP alert import");
        result.put("configured", ready);
        result.put("enabled", enabled && ready);
        result.put("status", !enabled ? "Off" : ready ? "Enabled" : "Not configured");
        result.put("lastAttempt", Objects.toString(configured.get("lastRunAt"), ""));
        result.put("lastSuccessfulSync", Objects.toString(configured.get("lastSuccessfulSyncAt"), ""));
        result.put("imported", Objects.requireNonNullElse((Integer) configured.get("lastImported"), 0));
        result.put("errors", Objects.toString(configured.get("lastError"), ""));
        result.put("error", Objects.toString(configured.get("lastError"), ""));
        return result;
    }

    public Map<String, Object> testConnection() {
        EmailAlertSettings settings = settingsService.load();
        try (Mailbox mailbox = connect(settings, false)) {
            int matching = matchingMessages(mailbox.folder(), settingsService.allowlist(settings)).size();
            return Map.of("ok", true, "matchingMessages", matching, "folder", settings.getFolderName());
        } catch (Exception error) {
            return Map.of("ok", false, "matchingMessages", 0, "message", safeMessage(error));
        }
    }

    public Map<String, Object> importAlerts() {
        if (!importing.compareAndSet(false, true))
            return Map.of("source", "Email alerts", "imported", 0, "skipped", true, "message", "An email alert import is already running.");
        int imported = 0;
        int failed = 0;
        EmailAlertSettings settings = null;
        try {
            settings = settingsService.load();
            if (!settings.isEnabled())
                return Map.of("source", "Email alerts", "imported", 0, "skipped", true, "message", "Email alerts are turned off.");
            try (Mailbox mailbox = connect(settings, settings.isMarkSeen())) {
            Map<String, List<String>> allowlist = settingsService.allowlist(settings);
            List<Message> matches = matchingMessages(mailbox.folder(), allowlist);
            for (Message message : matches) {
                try {
                    String messageId = messageId(message);
                    if (messageId.isBlank()) {
                        failed++;
                        continue;
                    }
                    if (processedMessages.existsById(messageId)) continue;
                    String provider = providerFor(message, allowlist);
                    AlertEmailParser parser = parsersByProvider.get(provider);
                    if (parser == null || (!parser.supports(message) && !EmailAlertSettingsService.senderAllowed(fromAddress(message), allowlist))) {
                        continue;
                    }
                    List<ParsedJob> parsed = parser.parse(message);
                    int messageImports = 0;
                    for (ParsedJob parsedJob : parsed) {
                        if (persistJob(provider, parsedJob)) messageImports++;
                    }
                    processedMessages.save(new EmailAlertProcessedMessage(messageId, messageImports));
                    imported += messageImports;
                    if (settings.isMarkSeen()) message.setFlag(Flags.Flag.SEEN, true);
                } catch (Exception parseError) {
                    failed++;
                    LOGGER.warn("Skipping an email-alert message after parser failure (message reference {}).",
                            messageReference(message));
                }
            }
            settings.setLastRunAt(Instant.now());
            settings.setLastImported(imported);
            settings.setLastError(failed == 0 ? "" : failed + " matching message(s) could not be processed.");
            if (failed == 0) settings.setLastSuccessfulSyncAt(settings.getLastRunAt());
            settingsRepository.save(settings);
            return Map.of("source", "Email alerts", "imported", imported, "skipped", false,
                    "errors", failed, "checkedAt", settings.getLastRunAt().toString());
            }
        } catch (Exception error) {
            if (settings != null) {
                settings.setLastRunAt(Instant.now());
                settings.setLastImported(imported);
                settings.setLastError(safeMessage(error));
                settingsRepository.save(settings);
            }
            return Map.of("source", "Email alerts", "imported", imported, "skipped", false,
                    "error", safeMessage(error), "checkedAt", Instant.now().toString());
        } finally {
            importing.set(false);
        }
    }

    private boolean persistJob(String provider, ParsedJob parsed) {
        if (parsed == null || parsed.title().isBlank() || parsed.url().isBlank()) return false;
        String url = canonicalListingUrl(parsed.url());
        if (url.isBlank() || jobs.findByUrl(url).isPresent()) return false;
        JobListing job = new JobListing();
        job.setTitle(parsed.title());
        job.setCompany(parsed.company());
        job.setLocation(parsed.location());
        job.setSalary(parsed.salary());
        job.setDescription(parsed.summary());
        job.setDescriptionStatus("summary only");
        job.setUrl(url);
        job.setApplyUrl(parsed.url());
        job.setApplicationUrlVerified(false);
        job.setSource(provider + " alert");
        job.setSourceJobId("");
        job.setAttributionUrl(url);
        job.setImportMethod("EMAIL_ALERT");
        job.setAvailabilityStatus("UNVERIFIED");
        job.setFirstSeenAt(Instant.now());
        job.setRetrievedAt(LocalDateTime.now());
        job.setLastSeenAt(null);
        job.setLastVerifiedAt(null);
        jobs.save(job);
        return true;
    }

    private Mailbox connect(EmailAlertSettings configuration, boolean markSeen) throws Exception {
        String password = settingsService.password(configuration);
        if (configuration.getEmailAddress().isBlank() || password.isBlank())
            throw new IllegalStateException("Configure the mailbox address and app password first.");
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.ssl.enable", "true");
        properties.put("mail.imaps.ssl.checkserveridentity", "true");
        properties.put("mail.imaps.connectiontimeout", "10000");
        properties.put("mail.imaps.timeout", "15000");
        properties.put("mail.imaps.writetimeout", "10000");
        Session session = Session.getInstance(properties);
        Store store = session.getStore("imaps");
        store.connect(configuration.getImapHost(), configuration.getImapPort(),
                configuration.getEmailAddress(), password.replaceAll("\\s+", ""));
        Folder folder = store.getFolder(configuration.getFolderName());
        if (!folder.exists()) {
            store.close();
            throw new IllegalStateException("The configured mailbox folder or label does not exist.");
        }
        folder.open(markSeen ? Folder.READ_WRITE : Folder.READ_ONLY);
        return new Mailbox(store, folder);
    }

    private List<Message> matchingMessages(Folder folder, Map<String, List<String>> allowlist) throws Exception {
        Message[] messages = folder.search(new FlagTerm(new Flags(Flags.Flag.DELETED), false));
        List<Message> matches = new ArrayList<>();
        for (Message message : messages) {
            if (EmailAlertSettingsService.senderAllowed(fromAddress(message), allowlist)) matches.add(message);
        }
        return matches;
    }

    private String providerFor(Message message, Map<String, List<String>> allowlist) throws Exception {
        String address = fromAddress(message);
        return allowlist.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(pattern -> addressMatches(address, pattern)))
                .map(Map.Entry::getKey).findFirst().orElse("");
    }

    private boolean addressMatches(String address, String pattern) {
        String sender = address.toLowerCase(Locale.ROOT);
        String allowed = pattern.toLowerCase(Locale.ROOT);
        return allowed.startsWith("*@") ? sender.endsWith(allowed.substring(1)) : sender.equals(allowed);
    }

    private String fromAddress(Message message) throws Exception {
        jakarta.mail.Address[] from = message.getFrom();
        if (from == null || from.length == 0) return "";
        if (from[0] instanceof InternetAddress internet) return internet.getAddress().trim().toLowerCase(Locale.ROOT);
        return from[0].toString().trim().toLowerCase(Locale.ROOT);
    }

    private String messageId(Message message) {
        try {
            String[] values = message.getHeader("Message-ID");
            return values == null || values.length == 0 ? "" : values[0].trim();
        } catch (Exception ignored) { return ""; }
    }

    private String messageReference(Message message) {
        try {
            String id = messageId(message);
            return id.isBlank() ? "missing-id" : Integer.toHexString(id.hashCode());
        } catch (Exception ignored) { return "unavailable"; }
    }

    private String safeMessage(Exception error) {
        if (error instanceof IllegalStateException && error.getMessage() != null) return error.getMessage();
        return "Mailbox connection or import failed (" + error.getClass().getSimpleName() + "). Check IMAP settings and app-password access.";
    }

    private static String canonicalListingUrl(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            URI uri = URI.create(value.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return "";
            return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort() == 443 ? -1 : uri.getPort(), uri.getPath().isBlank() ? "/" : uri.getPath(),
                    uri.getQuery(), null).toASCIIString();
        } catch (Exception ignored) { return ""; }
    }

    private record Mailbox(Store store, Folder folder) implements AutoCloseable {
        @Override public void close() throws Exception {
            try { if (folder.isOpen()) folder.close(false); }
            finally { if (store.isConnected()) store.close(); }
        }
    }
}
