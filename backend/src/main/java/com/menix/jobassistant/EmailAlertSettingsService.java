package com.menix.jobassistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
class EmailAlertCredentialCipher {
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    EmailAlertCredentialCipher(@Value("${app.email-alert-key-file:}") String configuredPath) {
        String value = configuredPath == null || configuredPath.isBlank()
                ? Path.of(System.getProperty("user.home"), ".menix-job-assistant", "email-alert.key").toString()
                : configuredPath;
        keyFile = Path.of(value).toAbsolutePath().normalize();
    }

    String encrypt(String value) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception error) {
            throw new IllegalStateException("Could not encrypt the mailbox app password locally.", error);
        }
    }

    String decrypt(String value) {
        try {
            byte[] packed = Base64.getDecoder().decode(value);
            if (packed.length <= IV_LENGTH) throw new IllegalArgumentException("Invalid encrypted mailbox credential.");
            byte[] iv = java.util.Arrays.copyOfRange(packed, 0, IV_LENGTH);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Could not decrypt the locally stored mailbox app password.", error);
        }
    }

    private SecretKey loadOrCreateKey() throws Exception {
        Files.createDirectories(keyFile.getParent());
        if (Files.exists(keyFile)) return new javax.crypto.spec.SecretKeySpec(Files.readAllBytes(keyFile), "AES");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        byte[] encoded = generator.generateKey().getEncoded();
        try {
            Files.write(keyFile, encoded, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            return new javax.crypto.spec.SecretKeySpec(Files.readAllBytes(keyFile), "AES");
        }
        try {
            Files.setPosixFilePermissions(keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            java.io.File file = keyFile.toFile();
            file.setReadable(false, false);
            file.setWritable(false, false);
            if (!file.setReadable(true, true) || !file.setWritable(true, true))
                throw new IllegalStateException("Could not restrict local mailbox key file permissions.");
        }
        return new javax.crypto.spec.SecretKeySpec(encoded, "AES");
    }
}

@Service
class EmailAlertSettingsService {
    private static final Map<String, List<String>> DEFAULT_ALLOWLIST = Map.of(
            "Naukri", List.of("*@naukri.com", "*@naukrimail.com"),
            "LinkedIn", List.of("jobalerts-noreply@linkedin.com"),
            "Indeed", List.of("*@indeed.com", "*@alert.indeed.com"),
            "Glassdoor", List.of("*@glassdoor.com"));
    private static final Pattern ADDRESS = Pattern.compile("(?:\\*|[a-z0-9.!#$%&'+/=?^_`{|}~-]+)@[a-z0-9.-]+");
    private final EmailAlertSettingsRepository settings;
    private final EmailAlertCredentialCipher cipher;
    private final ObjectMapper mapper;
    private final String environmentPassword;

    EmailAlertSettingsService(EmailAlertSettingsRepository settings, EmailAlertCredentialCipher cipher,
                              ObjectMapper mapper,
                              @Value("${app.email-alert-imap-password:}") String environmentPassword) {
        this.settings = settings;
        this.cipher = cipher;
        this.mapper = mapper;
        this.environmentPassword = environmentPassword == null ? "" : environmentPassword;
    }

    @Transactional
    public EmailAlertSettings load() {
        return settings.findById(1L).orElseGet(() -> {
            EmailAlertSettings created = new EmailAlertSettings();
            created.setAllowlistJson(writeAllowlist(DEFAULT_ALLOWLIST));
            return settings.save(created);
        });
    }

    public Map<String, Object> publicSettings() {
        EmailAlertSettings value = load();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imapHost", value.getImapHost());
        result.put("imapPort", value.getImapPort());
        result.put("emailAddress", value.getEmailAddress());
        result.put("folderName", value.getFolderName());
        result.put("enabled", value.isEnabled());
        result.put("markSeen", value.isMarkSeen());
        result.put("allowlist", parseAllowlist(value.getAllowlistJson()));
        result.put("hasAppPassword", !value.getEncryptedPassword().isBlank() || !environmentPassword.isBlank());
        result.put("passwordConfiguredByEnvironment", !environmentPassword.isBlank());
        result.put("lastRunAt", value.getLastRunAt());
        result.put("lastSuccessfulSyncAt", value.getLastSuccessfulSyncAt());
        result.put("lastImported", value.getLastImported());
        result.put("lastError", value.getLastError());
        return result;
    }

    @Transactional
    public EmailAlertSettings update(Map<String, Object> body) {
        EmailAlertSettings value = load();
        String host = text(body, "imapHost", value.getImapHost()).trim();
        String address = text(body, "emailAddress", value.getEmailAddress()).trim();
        String folder = text(body, "folderName", value.getFolderName()).trim();
        int port = integer(body.getOrDefault("imapPort", value.getImapPort()));
        if (!host.matches("[A-Za-z0-9.-]{1,255}") || host.contains(".."))
            throw new IllegalArgumentException("Enter a valid IMAP host name.");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("IMAP port must be between 1 and 65535.");
        if (!address.matches("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new IllegalArgumentException("Enter a valid mailbox email address.");
        if (folder.isBlank() || folder.length() > 255) throw new IllegalArgumentException("Enter a valid mailbox folder or label.");
        value.setImapHost(host);
        value.setImapPort(port);
        value.setEmailAddress(address);
        value.setFolderName(folder);
        value.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        value.setMarkSeen(Boolean.TRUE.equals(body.get("markSeen")));
        if (body.containsKey("appPassword")) {
            String password = Objects.toString(body.get("appPassword"), "");
            if (!password.isBlank()) value.setEncryptedPassword(cipher.encrypt(password));
        }
        if (body.containsKey("allowlist")) value.setAllowlistJson(writeAllowlist(validateAllowlist(body.get("allowlist"))));
        return settings.save(value);
    }

    String password(EmailAlertSettings value) {
        if (!environmentPassword.isBlank()) return environmentPassword;
        if (value.getEncryptedPassword().isBlank()) return "";
        return cipher.decrypt(value.getEncryptedPassword());
    }

    Map<String, List<String>> allowlist(EmailAlertSettings value) {
        return parseAllowlist(value.getAllowlistJson());
    }

    static boolean senderAllowed(String email, Map<String, List<String>> allowlist) {
        String candidate = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (candidate.isBlank()) return false;
        return allowlist.values().stream().flatMap(List::stream).anyMatch(pattern -> {
            String normalized = pattern.toLowerCase(Locale.ROOT);
            return normalized.startsWith("*@")
                    ? candidate.endsWith(normalized.substring(1))
                    : candidate.equals(normalized);
        });
    }

    private Map<String, List<String>> validateAllowlist(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Sender allowlist must be a provider-to-address list.");
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String provider : List.of("Naukri", "LinkedIn", "Indeed", "Glassdoor")) {
            Object entries = map.get(provider);
            if (!(entries instanceof List<?> list)) throw new IllegalArgumentException("Include a sender list for " + provider + ".");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (Object entry : list) {
                String pattern = Objects.toString(entry, "").trim().toLowerCase(Locale.ROOT);
                if (!ADDRESS.matcher(pattern).matches() || (pattern.startsWith("*@") && pattern.indexOf('*', 1) >= 0))
                    throw new IllegalArgumentException("Invalid sender address or domain pattern for " + provider + ".");
                unique.add(pattern);
            }
            result.put(provider, List.copyOf(unique));
        }
        return result;
    }

    private Map<String, List<String>> parseAllowlist(String json) {
        try {
            if (json == null || json.isBlank()) return DEFAULT_ALLOWLIST;
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("Stored email sender allowlist is invalid.", error);
        }
    }

    private String writeAllowlist(Map<String, List<String>> value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Could not save email sender allowlist.", error); }
    }

    private static String text(Map<String, Object> body, String key, String fallback) {
        return Objects.toString(body.getOrDefault(key, fallback), fallback);
    }

    private static int integer(Object value) {
        try { return Integer.parseInt(Objects.toString(value, "")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("IMAP port must be a number."); }
    }
}
