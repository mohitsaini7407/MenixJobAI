package com.menix.jobassistant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

@Service
public class CompanyBoardService {
    private static final Set<String> PROVIDERS = Set.of("ASHBY", "WORKABLE", "SMARTRECRUITERS");
    private final CompanyBoardConfigurationRepository boards;
    private final Map<String, CompanyBoardProviderConnector> connectors;

    public CompanyBoardService(CompanyBoardConfigurationRepository boards, List<JobSourceConnector> connectors) {
        this.boards = boards;
        this.connectors = connectors.stream()
                .filter(CompanyBoardProviderConnector.class::isInstance)
                .map(CompanyBoardProviderConnector.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        CompanyBoardProviderConnector::providerKey, connector -> connector));
    }

    public List<CompanyBoardConfiguration> list() {
        return boards.findAll().stream()
                .sorted(Comparator.comparing(CompanyBoardConfiguration::getProvider)
                        .thenComparing(CompanyBoardConfiguration::getCompany, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public CompanyBoardConfiguration add(Map<String, Object> body) {
        String provider = normalizedProvider(body.get("provider"));
        String identifier = bounded(body.get("identifier"), "identifier", 100, true);
        validateIdentifier(provider, identifier);
        String company = bounded(body.get("company"), "company", 180, true);
        boolean duplicate = boards.findByProviderIgnoreCase(provider).stream()
                .anyMatch(board -> board.getIdentifier().equalsIgnoreCase(identifier));
        if (duplicate) throw new IllegalArgumentException("This company board is already configured.");
        CompanyBoardConfiguration board = new CompanyBoardConfiguration();
        board.setProvider(provider);
        board.setIdentifier(identifier);
        board.setCompany(company);
        board.setEnabled(false);
        board.setTestStatus("UNVERIFIED");
        board.setLastError("");
        return boards.save(board);
    }

    @Transactional
    public CompanyBoardConfiguration update(Long id, Map<String, Object> body) {
        CompanyBoardConfiguration board = find(id);
        if (body.containsKey("enabled")) {
            boolean enabled = Boolean.parseBoolean(Objects.toString(body.get("enabled"), "false"));
            if (enabled && !"VERIFIED".equals(board.getTestStatus())) {
                throw new IllegalArgumentException("Test this company board successfully before enabling it.");
            }
            board.setEnabled(enabled);
        }
        if (body.containsKey("company")) board.setCompany(bounded(body.get("company"), "company", 180, true));
        board.setUpdatedAt(LocalDateTime.now());
        return boards.save(board);
    }

    @Transactional
    public Map<String, Object> test(Long id) {
        CompanyBoardConfiguration board = find(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("provider", board.getProvider());
        result.put("identifier", board.getIdentifier());
        result.put("testedAt", Instant.now().toString());
        try {
            int found = connector(board.getProvider()).testBoard(board.getIdentifier());
            board.setTestStatus("VERIFIED");
            board.setLastTestJobCount(found);
            board.setLastTestAt(Instant.now());
            board.setLastError("");
            board.setUpdatedAt(LocalDateTime.now());
            boards.save(board);
            result.put("ok", true);
            result.put("jobsFound", found);
            result.put("message", "Board verified. " + found + " job(s) found.");
        } catch (Exception error) {
            String message = safeError(error);
            board.setTestStatus("UNVERIFIED");
            board.setLastTestAt(Instant.now());
            board.setLastError(message);
            board.setUpdatedAt(LocalDateTime.now());
            boards.save(board);
            result.put("ok", false);
            result.put("jobsFound", 0);
            result.put("message", message);
        }
        return result;
    }

    public void delete(Long id) {
        boards.delete(find(id));
    }

    private CompanyBoardConfiguration find(Long id) {
        return boards.findById(id).orElseThrow(() -> new NoSuchElementException("Company board not found."));
    }

    private CompanyBoardProviderConnector connector(String provider) {
        CompanyBoardProviderConnector connector = connectors.get(provider);
        if (connector == null) throw new IllegalArgumentException("No board tester is registered for " + provider + ".");
        return connector;
    }

    private static String normalizedProvider(Object value) {
        String provider = Objects.toString(value, "").trim().toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Choose a supported employer-board provider.");
        return provider;
    }

    private static void validateIdentifier(String provider, String identifier) {
        if (!identifier.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Board identifiers may contain only letters, numbers, underscores, and hyphens.");
        }
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Choose a supported employer-board provider.");
    }

    private static String bounded(Object value, String name, int maxLength, boolean required) {
        String text = Objects.toString(value, "").trim();
        if (required && text.isBlank()) throw new IllegalArgumentException(name + " is required.");
        if (text.length() > maxLength) throw new IllegalArgumentException(name + " must be at most " + maxLength + " characters.");
        return text;
    }

    private static String safeError(Exception error) {
        String message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        return message.length() > 900 ? message.substring(0, 900) : message;
    }
}
