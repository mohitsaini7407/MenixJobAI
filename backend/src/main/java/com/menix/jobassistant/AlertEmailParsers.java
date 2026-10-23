package com.menix.jobassistant;

import jakarta.mail.Message;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ParsedJob(String title, String company, String location, String salary, String summary, String url) {}

interface AlertEmailParser {
    boolean supports(Message message);
    List<ParsedJob> parse(Message message) throws Exception;
}

abstract class AbstractAlertEmailParser implements AlertEmailParser {
    private static final Pattern SALARY = Pattern.compile(
            "(?i)(?:₹|INR|Rs\\.?\\s*)\\s?[\\d,]+(?:\\s*(?:-|to)\\s*(?:₹|INR|Rs\\.?\\s*)?\\s?[\\d,]+)?(?:\\s*(?:per annum|p\\.a\\.?|/year|per month|/month|LPA|Lakh))?");
    private static final List<String> ACTION_TEXT = List.of("apply", "view job", "view details", "see job", "learn more");

    protected abstract String provider();
    protected abstract String linkSelector();
    protected abstract boolean senderMatches(String address);

    @Override
    public boolean supports(Message message) {
        try {
            Address[] from = message.getFrom();
            if (from == null) return false;
            for (Address address : from) {
                String email = address instanceof InternetAddress internet
                        ? internet.getAddress() : address.toString();
                if (email != null && senderMatches(email.trim().toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        } catch (MessagingException ignored) {
            return false;
        }
    }

    @Override
    public List<ParsedJob> parse(Message message) throws Exception {
        String html = htmlBody(message);
        Document document = Jsoup.parse(html);
        Elements links = document.select(linkSelector());
        List<ParsedJob> parsed = new ArrayList<>();
        for (Element link : links) {
            ParsedJob job = parseCard(link);
            if (job != null) parsed.add(job);
        }
        return parsed;
    }

    private ParsedJob parseCard(Element link) {
        String href = link.attr("href").trim();
        if (!href.startsWith("https://")) return null;
        Element card = link;
        for (int depth = 0; depth < 5 && card.parent() != null; depth++) {
            card = card.parent();
            String text = card.text();
            if (text.length() > 100 && text.length() < 5000) break;
        }
        String title = firstText(card, "h1,h2,h3,[class*=title],[data-testid*=title]");
        if (title.isBlank()) title = link.text().trim();
        if (title.isBlank() || ACTION_TEXT.contains(title.toLowerCase(Locale.ROOT))) return null;
        String company = firstText(card, "[class*=company],[data-testid*=company],[class*=employer]");
        String location = firstText(card, "[class*=location],[data-testid*=location],[class*=place],[class*=metadata-item]");
        String visible = card.text();
        Matcher salaryMatcher = SALARY.matcher(visible);
        String salary = salaryMatcher.find() ? salaryMatcher.group().trim() : "";
        if (company.isBlank() || location.isBlank()) {
            String[] lines = visible.split("\\s{2,}|\\n");
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isBlank() || line.equalsIgnoreCase(title) || line.equalsIgnoreCase(provider())) continue;
                if (company.isBlank() && !line.matches("(?i).*(?:apply|view job|save job|salary|\\d+\\s*(?:years?|yrs?)).*")
                        && !line.equalsIgnoreCase(location)) company = line;
                else if (location.isBlank() && line.matches("(?i).*(?:remote|hybrid|india|city|\\b[A-Z][a-z]+,\\s*[A-Z]).*"))
                    location = line;
            }
        }
        String summary = visible.replace(title, " ").replace(company, " ").replace(location, " ")
                .replace(salary, " ").replaceAll("\\s+", " ").trim();
        return new ParsedJob(title, company, location, salary, summary, canonicalCleanUrl(href));
    }

    private static String firstText(Element root, String selector) {
        for (Element element : root.select(selector)) {
            String text = element.text().trim();
            if (!text.isBlank()) return text;
        }
        return "";
    }

    private static String htmlBody(Message message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String text) return text;
        if (content instanceof jakarta.mail.Multipart multipart) {
            StringBuilder html = new StringBuilder();
            for (int index = 0; index < multipart.getCount(); index++) {
                jakarta.mail.BodyPart part = multipart.getBodyPart(index);
                if (part.isMimeType("text/html")) html.append(Objects.toString(part.getContent(), ""));
                else if (part.isMimeType("multipart/*") && part.getContent() instanceof jakarta.mail.Multipart nested)
                    html.append(htmlBody(nested));
            }
            return html.toString();
        }
        return "";
    }

    private static String htmlBody(jakarta.mail.Multipart multipart) throws Exception {
        StringBuilder html = new StringBuilder();
        for (int index = 0; index < multipart.getCount(); index++) {
            jakarta.mail.BodyPart part = multipart.getBodyPart(index);
            if (part.isMimeType("text/html")) html.append(Objects.toString(part.getContent(), ""));
            else if (part.isMimeType("multipart/*") && part.getContent() instanceof jakarta.mail.Multipart nested)
                html.append(htmlBody(nested));
        }
        return html.toString();
    }

    private String canonicalCleanUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String parameter : query.split("&")) {
                    String[] pair = parameter.split("=", 2);
                    if (pair.length != 2 || !List.of("url", "joburl", "job_url", "destination", "redirect_url")
                            .contains(pair[0].toLowerCase(Locale.ROOT))) continue;
                    String decoded = java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
                    URI candidate = URI.create(decoded);
                    if ("https".equalsIgnoreCase(candidate.getScheme()) && candidate.getHost() != null
                            && candidate.getUserInfo() == null) {
                        return candidate.normalize().toASCIIString();
                    }
                }
            }
            return raw;
        } catch (Exception ignored) {
            return raw;
        }
    }
}

@Component
class NaukriAlertEmailParser extends AbstractAlertEmailParser {
    protected String provider() { return "Naukri"; }
    protected String linkSelector() { return "a[href*='naukri.com'], a[href*='naukrimail.com']"; }
    protected boolean senderMatches(String address) { return address.endsWith("@naukri.com") || address.endsWith("@naukrimail.com"); }
}

@Component
class LinkedInAlertEmailParser extends AbstractAlertEmailParser {
    protected String provider() { return "LinkedIn"; }
    protected String linkSelector() { return "a[href*='linkedin.com/jobs']"; }
    protected boolean senderMatches(String address) { return address.equals("jobalerts-noreply@linkedin.com"); }
}

@Component
class IndeedAlertEmailParser extends AbstractAlertEmailParser {
    protected String provider() { return "Indeed"; }
    protected String linkSelector() { return "a[href*='indeed.com']"; }
    protected boolean senderMatches(String address) { return address.endsWith("@indeed.com") || address.endsWith("@alert.indeed.com"); }
}

@Component
class GlassdoorAlertEmailParser extends AbstractAlertEmailParser {
    protected String provider() { return "Glassdoor"; }
    protected String linkSelector() { return "a[href*='glassdoor.com']"; }
    protected boolean senderMatches(String address) { return address.endsWith("@glassdoor.com"); }
}
