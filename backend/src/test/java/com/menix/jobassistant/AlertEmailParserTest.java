package com.menix.jobassistant;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AlertEmailParserTest {
    @TempDir Path temp;

    @Test
    void providerParsersReadBothAnonymizedHtmlSamples() throws Exception {
        assertProvider("naukri", "alerts@naukri.com", new NaukriAlertEmailParser(),
                "Junior Full Stack Developer", "Example Systems", "Jaipur, Rajasthan");
        assertProvider("linkedin", "jobalerts-noreply@linkedin.com", new LinkedInAlertEmailParser(),
                "Software Engineer", "Sample Tech", "Bengaluru, Karnataka");
        assertProvider("indeed", "alerts@indeed.com", new IndeedAlertEmailParser(),
                "Java Developer", "Sample Services", "Pune, Maharashtra");
        assertProvider("glassdoor", "alerts@glassdoor.com", new GlassdoorAlertEmailParser(),
                "Associate React Developer", "Sample Digital", "Jaipur, India");
    }

    @Test
    void parserCanonicalizesEmbeddedCleanHttpsUrlButKeepsTrackingRedirectOtherwise() throws Exception {
        NaukriAlertEmailParser parser = new NaukriAlertEmailParser();
        MimeMessage tracked = message("alerts@naukri.com", """
                <div class="job-card"><h2>React Developer</h2><span class="company">Example</span>
                <a href="https://www.naukri.com/track?url=https%3A%2F%2Fcareers.example.com%2Fjobs%2Freact%3Fref%3Dmail">View job</a></div>
                """);
        MimeMessage unchanged = message("alerts@naukri.com", """
                <div class="job-card"><h2>Java Engineer</h2><span class="company">Example</span>
                <a href="https://www.naukri.com/track/opaque-token">View job</a></div>
                """);

        assertEquals("https://careers.example.com/jobs/react?ref=mail", parser.parse(tracked).get(0).url());
        assertEquals("https://www.naukri.com/track/opaque-token", parser.parse(unchanged).get(0).url());
    }

    @Test
    void allowlistRequiresExactAddressOrConfiguredDomainWildcard() {
        Map<String, List<String>> allowlist = Map.of("Naukri", List.of("*@naukri.com"),
                "LinkedIn", List.of("jobalerts-noreply@linkedin.com"));
        assertTrue(EmailAlertSettingsService.senderAllowed("alerts@naukri.com", allowlist));
        assertTrue(EmailAlertSettingsService.senderAllowed("jobalerts-noreply@linkedin.com", allowlist));
        assertFalse(EmailAlertSettingsService.senderAllowed("alerts@other.com", allowlist));
        assertFalse(EmailAlertSettingsService.senderAllowed("other@linkedin.com", allowlist));
    }

    @Test
    void mailboxPasswordCipherEncryptsLocallyAndNeverReturnsPlaintextStorage() throws Exception {
        Path keyPath = temp.resolve("email-alert.key");
        EmailAlertCredentialCipher cipher = new EmailAlertCredentialCipher(keyPath.toString());

        String encrypted = cipher.encrypt("app-password-secret");

        assertNotEquals("app-password-secret", encrypted);
        assertFalse(encrypted.contains("app-password-secret"));
        assertEquals("app-password-secret", cipher.decrypt(encrypted));
        assertTrue(Files.exists(keyPath));
    }

    private void assertProvider(String fixturePrefix, String sender, AlertEmailParser parser,
                                String expectedTitle, String expectedCompany, String expectedLocation) throws Exception {
        for (int sample = 1; sample <= 2; sample++) {
            String html;
            try (var stream = getClass().getResourceAsStream("/email-alerts/" + fixturePrefix + "-" + sample + ".html")) {
                assertNotNull(stream);
                html = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            MimeMessage message = message(sender, html);
            assertTrue(parser.supports(message));
            List<ParsedJob> jobs = parser.parse(message);
            assertEquals(1, jobs.size());
            assertFalse(jobs.get(0).title().isBlank());
            assertFalse(jobs.get(0).company().isBlank());
            assertFalse(jobs.get(0).location().isBlank());
            assertTrue(jobs.get(0).url().startsWith("https://"));
            assertFalse(jobs.get(0).summary().contains("<"));
            if (sample == 1) {
                assertEquals(expectedTitle, jobs.get(0).title());
                assertEquals(expectedCompany, jobs.get(0).company());
                assertEquals(expectedLocation, jobs.get(0).location());
                assertFalse(jobs.get(0).salary().isBlank());
            }
        }
    }

    private MimeMessage message(String from, String html) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        message.setContent(html, "text/html; charset=UTF-8");
        message.setHeader("Message-ID", "<sample-" + Integer.toHexString(html.hashCode()) + "@example.test>");
        message.saveChanges();
        return message;
    }
}
