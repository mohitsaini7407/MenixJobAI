package com.menix.jobassistant;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ResumeParserService {
    private static final int MAX_FIELD_CHARS = 3500;
    private static final List<String> SKILLS = List.of(
        "Java", "Spring Boot", "Spring", "SQL", "PostgreSQL", "MySQL", "MongoDB", "Python", "JavaScript", "TypeScript",
        "React", "Angular", "Vue", "Node.js", "Express", "HTML", "CSS", "REST API", "GraphQL", "Docker", "Kubernetes",
        "AWS", "Azure", "GCP", "Git", "GitHub", "Hibernate", "JPA", "Microservices", "Redis", "Kafka", "Linux",
        "Jenkins", "CI/CD", "JUnit", "Selenium", "C++", "C#", ".NET", "Go", "Rust", "Figma", "Excel", "Power BI"
    );
    private static final Set<String> SECTION_HEADINGS = Set.of("summary", "professional summary", "profile", "objective", "skills",
        "technical skills", "experience", "work experience", "professional experience", "employment", "education", "academic background",
        "projects", "personal projects", "certifications", "certificates", "achievements", "awards", "interests", "languages", "references");

    public Map<String, String> suggestions(MultipartFile file) throws Exception {
        String text = extract(file);
        if (text.isBlank()) throw new IllegalArgumentException("No selectable text was found. Scanned resumes need OCR before they can be imported.");
        List<String> lines = Arrays.stream(text.replace('\u0000', ' ').replace("\r", "\n").split("\n"))
            .map(String::trim).filter(s -> !s.isBlank()).toList();
        Map<String, String> result = new LinkedHashMap<>();

        lines.stream().limit(10).filter(ResumeParserService::looksLikeName).findFirst().ifPresent(s -> result.put("fullName", clean(s)));
        Matcher email = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}").matcher(text);
        if (email.find()) result.put("email", email.group());
        Pattern phonePattern = Pattern.compile("(?<!\\w)(?:\\+?\\d{1,3}[ .-]?)?(?:\\(?\\d{2,4}\\)?[ .-]?)?\\d{3,4}[ .-]?\\d{3,4}(?!\\w)");
        Matcher phone = phonePattern.matcher(text);
        while (phone.find()) { String candidate=phone.group().trim(); long digits=candidate.chars().filter(Character::isDigit).count(); if(digits>=10&&digits<=15){result.put("phone",candidate);break;} }
        addLink(result, text, "linkedinUrl", "(?i)(?:https?://)?(?:www\\.)?linkedin\\.com/in/[A-Za-z0-9._%/-]+");
        addLink(result, text, "githubUrl", "(?i)(?:https?://)?(?:www\\.)?github\\.com/[A-Za-z0-9-]+");

        lines.stream().limit(12).filter(ResumeParserService::looksLikeTitle).findFirst().ifPresent(s -> {
            String title = clean(s);
            result.put("headline", title);
            result.put("preferredTitles", title);
        });

        String foundSkills = SKILLS.stream().filter(skill -> containsTerm(text, skill)).collect(Collectors.joining(", "));
        if (!foundSkills.isBlank()) result.put("skills", foundSkills);
        putSection(result, "experience", lines, Set.of("experience", "work experience", "professional experience", "employment"));
        putSection(result, "education", lines, Set.of("education", "academic background"));
        putSection(result, "projects", lines, Set.of("projects", "personal projects"));
        return result;
    }

    private String extract(MultipartFile file) throws Exception {
        String filename = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        byte[] bytes = file.getBytes();
        if (filename.endsWith(".txt")) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (filename.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(bytes)) { return new PDFTextStripper().getText(document); }
        }
        if (filename.endsWith(".docx")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes)); XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }
        }
        if (filename.endsWith(".doc")) {
            try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes)); WordExtractor extractor = new WordExtractor(document)) {
                return extractor.getText();
            }
        }
        throw new IllegalArgumentException("Supported resume formats: PDF, DOCX, DOC and TXT.");
    }

    private static void putSection(Map<String, String> target, String key, List<String> lines, Set<String> headings) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (headings.contains(normalizeHeading(lines.get(i)))) { start = i + 1; break; }
        }
        if (start < 0 || start >= lines.size()) return;
        List<String> selected = new ArrayList<>();
        for (int i = start; i < lines.size() && selected.size() < 30; i++) {
            if (SECTION_HEADINGS.contains(normalizeHeading(lines.get(i)))) break;
            selected.add(lines.get(i));
        }
        String value = String.join("\n", selected).trim();
        if (!value.isBlank()) { int limit=key.equals("education")?1900:MAX_FIELD_CHARS; target.put(key, value.substring(0, Math.min(value.length(), limit))); }
    }

    private static void addLink(Map<String,String> target,String text,String key,String expression){Matcher matcher=Pattern.compile(expression).matcher(text);if(matcher.find()){String link=matcher.group().replaceAll("[),.;]+$","");if(!link.toLowerCase(Locale.ROOT).startsWith("http"))link="https://"+link;target.put(key,link);}}

    private static String normalizeHeading(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean looksLikeName(String value) {
        String line = clean(value);
        if (line.length() < 4 || line.length() > 65 || line.contains("@") || line.matches(".*\\d.*") || line.contains("|")) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        if (SECTION_HEADINGS.contains(normalizeHeading(line)) || lower.contains("resume") || lower.contains("curriculum vitae")) return false;
        return line.matches("[\\p{L}][\\p{L} .'-]{2,64}") && line.split("\\s+").length >= 2;
    }

    private static boolean looksLikeTitle(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return value.length() <= 100 && List.of("developer", "engineer", "analyst", "designer", "manager", "intern", "consultant", "administrator", "architect", "scientist", "specialist")
            .stream().anyMatch(lower::contains);
    }

    private static boolean containsTerm(String text, String term) {
        String expression = "(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])";
        return Pattern.compile(expression).matcher(text).find();
    }

    private static String clean(String value) { return value.replaceAll("\\s+", " ").trim(); }
}
