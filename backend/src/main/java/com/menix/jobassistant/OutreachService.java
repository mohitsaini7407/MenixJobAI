package com.menix.jobassistant;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class OutreachService {
    private final OutreachRepository outreach;
    private final JobRepository jobs;

    public OutreachService(OutreachRepository outreach, JobRepository jobs) {
        this.outreach = outreach;
        this.jobs = jobs;
    }

    public List<OutreachRecord> list() {
        return outreach.findAll().stream()
                .sorted(Comparator.comparing(OutreachRecord::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public OutreachRecord create(Map<String, Object> input) {
        OutreachRecord record = new OutreachRecord();
        apply(record, input);
        record.setStatus("DRAFT");
        record.setUpdatedAt(LocalDateTime.now());
        return outreach.save(record);
    }

    public OutreachRecord update(Long id, Map<String, Object> input) {
        OutreachRecord record = find(id);
        if ("SENT".equals(record.getStatus())) {
            throw new IllegalArgumentException("Sent outreach records are read-only.");
        }
        apply(record, input);
        record.setUpdatedAt(LocalDateTime.now());
        return outreach.save(record);
    }

    public OutreachRecord confirmSent(Long id, String evidence) {
        OutreachRecord record = find(id);
        if ("SENT".equals(record.getStatus())) {
            throw new IllegalArgumentException("This outreach message has already been confirmed as sent.");
        }
        if (record.getDraftMessage().isBlank()) {
            throw new IllegalArgumentException("Add a message draft before confirming it was sent.");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("Confirm external sending and describe the confirmation evidence.");
        }
        record.setStatus("SENT");
        record.setSentAt(LocalDateTime.now());
        record.setConfirmationEvidence(limit(evidence, 1000));
        record.setUpdatedAt(LocalDateTime.now());
        return outreach.save(record);
    }

    private OutreachRecord find(Long id) {
        return outreach.findById(id).orElseThrow(() -> new NoSuchElementException("Outreach record not found."));
    }

    private void apply(OutreachRecord record, Map<String, Object> input) {
        if (input.containsKey("portal")) record.setPortal(value(input, "portal", 40, "External"));
        if (input.containsKey("contactName")) record.setContactName(value(input, "contactName", 180, ""));
        if (input.containsKey("company")) record.setCompany(value(input, "company", 180, ""));
        if (input.containsKey("profileUrl")) record.setProfileUrl(publicHttps(value(input, "profileUrl", 2048, "")));
        if (input.containsKey("jobUrl")) record.setJobUrl(publicHttps(value(input, "jobUrl", 2048, "")));
        if (input.containsKey("jobId")) {
            String raw = Objects.toString(input.get("jobId"), "").trim();
            if (raw.isBlank()) record.setJobId(null);
            else {
                try {
                    Long id = Long.valueOf(raw);
                    if (!jobs.existsById(id)) throw new IllegalArgumentException("Related job was not found.");
                    record.setJobId(id);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Related job id is invalid.");
                }
            }
        }
        if (input.containsKey("draftMessage")) record.setDraftMessage(value(input, "draftMessage", 8000, ""));
        if (input.containsKey("notes")) record.setNotes(value(input, "notes", 3000, ""));
        if (input.containsKey("followUpDate")) {
            String raw = Objects.toString(input.get("followUpDate"), "").trim();
            try { record.setFollowUpDate(raw.isBlank() ? null : LocalDate.parse(raw)); }
            catch (Exception error) { throw new IllegalArgumentException("Follow-up date must use YYYY-MM-DD."); }
        }
    }

    private static String value(Map<String, Object> input, String key, int max, String fallback) {
        String value = Objects.toString(input.get(key), fallback).trim();
        if (value.length() > max) throw new IllegalArgumentException(key + " is too long.");
        return value;
    }

    private static String limit(String value, int max) {
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(trimmed.length(), max));
    }

    private static String publicHttps(String value) {
        if (value.isBlank()) return "";
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")) {
                throw new IllegalArgumentException("Profile and job links must be public HTTPS URLs.");
            }
            return uri.normalize().toASCIIString();
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("Profile and job links")) throw error;
            throw new IllegalArgumentException("Enter a valid public HTTPS URL.");
        }
    }
}
