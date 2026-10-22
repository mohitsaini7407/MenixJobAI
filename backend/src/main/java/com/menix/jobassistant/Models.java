package com.menix.jobassistant;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "candidate_profile")
class CandidateProfile {
    @Id private Long id = 1L;
    private String fullName = "";
    private String email = "";
    private String phone = "";
    private String currentLocation = "";
    private String linkedinUrl = "";
    private String githubUrl = "";
    private String portfolioUrl = "";
    private String headline = "";
    @Column(length = 4000) private String skills = "";
    @Column(length = 2000) private String preferredTitles = "";
    @Column(length = 2000) private String preferredLocations = "";
    @Column(length = 2000) private String education = "";
    private String minimumSalary = "";
    private String degree = "";
    private String graduationYear = "";
    private String noticePeriod = "";
    private String workAuthorization = "";
    private String sponsorshipAnswer = "";
    @Column(length = 4000) private String projects = "";
    @Column(length = 4000) private String experience = "";
    private String experienceLevel = "Fresher / entry level";
    public CandidateProfile() {}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getFullName(){return fullName;} public void setFullName(String v){fullName=safe(v);}
    public String getEmail(){return email;} public void setEmail(String v){email=safe(v);}
    public String getPhone(){return phone;} public void setPhone(String v){phone=safe(v);}
    public String getCurrentLocation(){return currentLocation;} public void setCurrentLocation(String v){currentLocation=safe(v);}
    public String getLinkedinUrl(){return linkedinUrl;} public void setLinkedinUrl(String v){linkedinUrl=safe(v);}
    public String getGithubUrl(){return githubUrl;} public void setGithubUrl(String v){githubUrl=safe(v);}
    public String getPortfolioUrl(){return portfolioUrl;} public void setPortfolioUrl(String v){portfolioUrl=safe(v);}
    public String getHeadline(){return headline;} public void setHeadline(String v){headline=safe(v);}
    public String getSkills(){return skills;} public void setSkills(String v){skills=safe(v);}
    public String getPreferredTitles(){return preferredTitles;} public void setPreferredTitles(String v){preferredTitles=safe(v);}
    public String getPreferredLocations(){return preferredLocations;} public void setPreferredLocations(String v){preferredLocations=safe(v);}
    public String getEducation(){return education;} public void setEducation(String v){education=safe(v);}
    public String getMinimumSalary(){return minimumSalary;} public void setMinimumSalary(String v){minimumSalary=safe(v);}
    public String getDegree(){return degree;} public void setDegree(String v){degree=safe(v);}
    public String getGraduationYear(){return graduationYear;} public void setGraduationYear(String v){graduationYear=safe(v);}
    public String getNoticePeriod(){return noticePeriod;} public void setNoticePeriod(String v){noticePeriod=safe(v);}
    public String getWorkAuthorization(){return workAuthorization;} public void setWorkAuthorization(String v){workAuthorization=safe(v);}
    public String getSponsorshipAnswer(){return sponsorshipAnswer;} public void setSponsorshipAnswer(String v){sponsorshipAnswer=safe(v);}
    public String getProjects(){return projects;} public void setProjects(String v){projects=safe(v);}
    public String getExperience(){return experience;} public void setExperience(String v){experience=safe(v);}
    public String getExperienceLevel(){return experienceLevel;} public void setExperienceLevel(String v){experienceLevel=safe(v);}
    static String safe(String v){return v == null ? "" : v.trim();}
}

@Entity
@Table(name="job_listing", uniqueConstraints=@UniqueConstraint(columnNames={"sourceUrl"}))
class JobListing {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String title = ""; private String company = ""; private String location = "";
    private String url = ""; private String applyUrl = ""; private String source = "External Link"; private String employmentType = "";
    private String experienceLevel = ""; @Column(length=8000) private String description = "";
    @Column(length=2000) private String requiredSkills = "";
    private LocalDate publishedAt; private LocalDateTime retrievedAt = LocalDateTime.now();
    private Instant postedAt; private Boolean exactPostingTime; private String workMode = "";
    @Column(length=2000) private String experienceRequirements = "";
    private String salary = ""; private String sourceJobId = ""; private String attributionUrl = "";
    private Instant firstSeenAt = Instant.now();
    private Instant lastSeenAt;
    private Instant lastVerifiedAt;
    private Instant lastSuccessfulSyncAt;
    private Instant closedAt;
    private String availabilityStatus = "UNVERIFIED";
    private String importMethod = "MANUAL";
    private LocalDateTime lastUpdatedAt = LocalDateTime.now();
    private String aiMatchCacheKey = "";
    @Column(length=12000) private String aiMatchJson = "";
    private String aiMatchCachedAt = "";
    public JobListing() {}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=CandidateProfile.safe(v);}
    public String getCompany(){return company;} public void setCompany(String v){company=CandidateProfile.safe(v);}
    public String getLocation(){return location;} public void setLocation(String v){location=CandidateProfile.safe(v);}
    public String getUrl(){return url;} public void setUrl(String v){url=CandidateProfile.safe(v);}
    public String getApplyUrl(){return applyUrl;} public void setApplyUrl(String v){applyUrl=CandidateProfile.safe(v);}
    public String getSource(){return source;} public void setSource(String v){source=CandidateProfile.safe(v);}
    public String getEmploymentType(){return employmentType;} public void setEmploymentType(String v){employmentType=CandidateProfile.safe(v);}
    public String getExperienceLevel(){return experienceLevel;} public void setExperienceLevel(String v){experienceLevel=CandidateProfile.safe(v);}
    public String getDescription(){return description;} public void setDescription(String v){description=CandidateProfile.safe(v);}
    public String getRequiredSkills(){return requiredSkills;} public void setRequiredSkills(String v){requiredSkills=CandidateProfile.safe(v);}
    public LocalDate getPublishedAt(){return publishedAt;} public void setPublishedAt(LocalDate v){publishedAt=v;}
    public LocalDateTime getRetrievedAt(){return retrievedAt;} public void setRetrievedAt(LocalDateTime v){retrievedAt=v;}
    public Instant getPostedAt(){return postedAt;} public void setPostedAt(Instant v){postedAt=v;}
    public boolean isExactPostingTime(){return Boolean.TRUE.equals(exactPostingTime);} public void setExactPostingTime(Boolean v){exactPostingTime=v;}
    public String getWorkMode(){return workMode;} public void setWorkMode(String v){workMode=CandidateProfile.safe(v);}
    public String getExperienceRequirements(){return experienceRequirements;} public void setExperienceRequirements(String v){experienceRequirements=CandidateProfile.safe(v);}
    public String getSalary(){return salary;} public void setSalary(String v){salary=CandidateProfile.safe(v);}
    public String getSourceJobId(){return sourceJobId;} public void setSourceJobId(String v){sourceJobId=CandidateProfile.safe(v);}
    public String getAttributionUrl(){return attributionUrl;} public void setAttributionUrl(String v){attributionUrl=CandidateProfile.safe(v);}
    public Instant getFirstSeenAt(){return firstSeenAt;} public void setFirstSeenAt(Instant v){firstSeenAt=v;}
    public Instant getLastSeenAt(){return lastSeenAt;} public void setLastSeenAt(Instant v){lastSeenAt=v;}
    public Instant getLastVerifiedAt(){return lastVerifiedAt;} public void setLastVerifiedAt(Instant v){lastVerifiedAt=v;}
    public Instant getLastSuccessfulSyncAt(){return lastSuccessfulSyncAt;} public void setLastSuccessfulSyncAt(Instant v){lastSuccessfulSyncAt=v;}
    public Instant getClosedAt(){return closedAt;} public void setClosedAt(Instant v){closedAt=v;}
    public String getAvailabilityStatus(){return availabilityStatus;} public void setAvailabilityStatus(String v){availabilityStatus=CandidateProfile.safe(v);}
    public String getImportMethod(){return importMethod;} public void setImportMethod(String v){importMethod=CandidateProfile.safe(v);}
    public LocalDateTime getLastUpdatedAt(){return lastUpdatedAt;} public void setLastUpdatedAt(LocalDateTime v){lastUpdatedAt=v;}
    public String getAiMatchCacheKey(){return aiMatchCacheKey;} public void setAiMatchCacheKey(String v){aiMatchCacheKey=CandidateProfile.safe(v);}
    public String getAiMatchJson(){return aiMatchJson;} public void setAiMatchJson(String v){aiMatchJson=CandidateProfile.safe(v);}
    public String getAiMatchCachedAt(){return aiMatchCachedAt;} public void setAiMatchCachedAt(String v){aiMatchCachedAt=CandidateProfile.safe(v);}
}

@Entity
@Table(name="job_source_sync_state")
class JobSourceSyncState {
    @Id @Column(length=120) private String source;
    private Instant lastAttemptAt;
    private Instant lastSuccessfulSyncAt;
    private Integer fetched = 0;
    private Integer imported = 0;
    private Integer updated = 0;
    private Integer rejected = 0;
    private Integer deduplicated = 0;
    @Column(length=2000) private String latestError = "";
    public JobSourceSyncState(){}
    public JobSourceSyncState(String source){this.source=CandidateProfile.safe(source);}
    public String getSource(){return source;} public void setSource(String v){source=CandidateProfile.safe(v);}
    public Instant getLastAttemptAt(){return lastAttemptAt;} public void setLastAttemptAt(Instant v){lastAttemptAt=v;}
    public Instant getLastSuccessfulSyncAt(){return lastSuccessfulSyncAt;} public void setLastSuccessfulSyncAt(Instant v){lastSuccessfulSyncAt=v;}
    public Integer getFetched(){return fetched;} public void setFetched(Integer v){fetched=v;}
    public Integer getImported(){return imported;} public void setImported(Integer v){imported=v;}
    public Integer getUpdated(){return updated;} public void setUpdated(Integer v){updated=v;}
    public Integer getRejected(){return rejected;} public void setRejected(Integer v){rejected=v;}
    public Integer getDeduplicated(){return deduplicated;} public void setDeduplicated(Integer v){deduplicated=v;}
    public String getLatestError(){return latestError;} public void setLatestError(String v){latestError=CandidateProfile.safe(v);}
}

@Entity
@Table(name="job_application")
class JobApplication {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long jobId; private String jobTitle = ""; private String company = "";
    private String status = "SHORTLISTED"; @Column(length=3000) private String notes = "";
    @Column(length=1000) private String submissionEvidence = "";
    private LocalDate appliedAt; private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    public JobApplication() {}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getJobId(){return jobId;} public void setJobId(Long v){jobId=v;}
    public String getJobTitle(){return jobTitle;} public void setJobTitle(String v){jobTitle=CandidateProfile.safe(v);}
    public String getCompany(){return company;} public void setCompany(String v){company=CandidateProfile.safe(v);}
    public String getStatus(){return status;} public void setStatus(String v){status=CandidateProfile.safe(v);}
    public String getNotes(){return notes;} public void setNotes(String v){notes=CandidateProfile.safe(v);}
    public String getSubmissionEvidence(){return submissionEvidence;} public void setSubmissionEvidence(String v){submissionEvidence=CandidateProfile.safe(v);}
    public LocalDate getAppliedAt(){return appliedAt;} public void setAppliedAt(LocalDate v){appliedAt=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}

@Entity
@Table(name="autofill_session")
class AutofillSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long jobId;
    private Long applicationId;
    @Column(nullable=false, length=32) private String status="READY";
    private int fieldsDetected;
    private int fieldsFilled;
    @Column(length=1000) private String diagnostic="";
    private LocalDateTime createdAt=LocalDateTime.now();
    private LocalDateTime updatedAt=LocalDateTime.now();
    public AutofillSession() {}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public Long getJobId(){return jobId;} public void setJobId(Long v){jobId=v;}
    public Long getApplicationId(){return applicationId;} public void setApplicationId(Long v){applicationId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=CandidateProfile.safe(v);}
    public int getFieldsDetected(){return fieldsDetected;} public void setFieldsDetected(int v){fieldsDetected=v;}
    public int getFieldsFilled(){return fieldsFilled;} public void setFieldsFilled(int v){fieldsFilled=v;}
    public String getDiagnostic(){return diagnostic;} public void setDiagnostic(String v){diagnostic=CandidateProfile.safe(v);}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}

@Entity
@Table(name="smart_apply_answer", indexes=@Index(name="idx_smart_apply_question_key", columnList="questionKey"))
class SmartApplyAnswer {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=1000) private String question="";
    @Column(nullable=false, length=1000, unique=true) private String questionKey="";
    @Column(nullable=false, length=8000) private String answer="";
    @Column(nullable=false, length=80) private String category="General";
    @Column(nullable=false, length=32) private String source="Manual";
    private Instant approvedAt=Instant.now();
    private Instant lastUsedAt;
    private int usageCount;
    private int correctionCount;
    private boolean excluded;
    public SmartApplyAnswer(){}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getQuestion(){return question;} public void setQuestion(String v){question=CandidateProfile.safe(v);}
    public String getQuestionKey(){return questionKey;} public void setQuestionKey(String v){questionKey=CandidateProfile.safe(v);}
    public String getAnswer(){return answer;} public void setAnswer(String v){answer=CandidateProfile.safe(v);}
    public String getCategory(){return category;} public void setCategory(String v){category=CandidateProfile.safe(v);}
    public String getSource(){return source;} public void setSource(String v){source=CandidateProfile.safe(v);}
    public Instant getApprovedAt(){return approvedAt;} public void setApprovedAt(Instant v){approvedAt=v;}
    public Instant getLastUsedAt(){return lastUsedAt;} public void setLastUsedAt(Instant v){lastUsedAt=v;}
    public int getUsageCount(){return usageCount;} public void setUsageCount(int v){usageCount=Math.max(0,v);}
    public int getCorrectionCount(){return correctionCount;} public void setCorrectionCount(int v){correctionCount=Math.max(0,v);}
    public boolean isExcluded(){return excluded;} public void setExcluded(boolean v){excluded=v;}
}

@Entity
@Table(name="smart_apply_preferences")
class SmartApplyPreferences {
    @Id private Long id=1L;
    private boolean automaticReuseApprovedAnswers;
    private boolean learningEnabled=true;
    private double highConfidenceThreshold=0.85;
    private double mediumConfidenceThreshold=0.55;
    public SmartApplyPreferences(){}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public boolean isAutomaticReuseApprovedAnswers(){return automaticReuseApprovedAnswers;}
    public void setAutomaticReuseApprovedAnswers(boolean v){automaticReuseApprovedAnswers=v;}
    public boolean isLearningEnabled(){return learningEnabled;}
    public void setLearningEnabled(boolean v){learningEnabled=v;}
    public double getHighConfidenceThreshold(){return highConfidenceThreshold;}
    public void setHighConfidenceThreshold(double v){highConfidenceThreshold=v;}
    public double getMediumConfidenceThreshold(){return mediumConfidenceThreshold;}
    public void setMediumConfidenceThreshold(double v){mediumConfidenceThreshold=v;}
}

@Entity
@Table(name="job_source_connection", uniqueConstraints=@UniqueConstraint(columnNames={"sourceUrl"}))
class JobSourceConnection {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=180) private String name="";
    @Column(nullable=false, length=2048) private String sourceUrl="";
    @Column(nullable=false, length=40) private String sourceType="CAREER_SITE";
    @Column(nullable=false) private boolean enabled=true;
    @Column(length=40) private String detectedFormat="UNKNOWN";
    @Column(length=40) private String connectionStatus="NOT_TESTED";
    @Column(length=1000) private String termsNotes="Verify provider terms and permissions before enabling automated retrieval.";
    @Column(length=1000) private String rateLimitNotes="Use conservative refresh intervals and honor HTTP 429 / Retry-After.";
    @Column(length=2000) private String lastError="";
    private LocalDateTime lastTestAt;
    private LocalDateTime lastSyncAt;
    private LocalDateTime createdAt=LocalDateTime.now();
    public JobSourceConnection(){}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getName(){return name;} public void setName(String v){name=CandidateProfile.safe(v);}
    public String getSourceUrl(){return sourceUrl;} public void setSourceUrl(String v){sourceUrl=CandidateProfile.safe(v);}
    public String getSourceType(){return sourceType;} public void setSourceType(String v){sourceType=CandidateProfile.safe(v);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getDetectedFormat(){return detectedFormat;} public void setDetectedFormat(String v){detectedFormat=CandidateProfile.safe(v);}
    public String getConnectionStatus(){return connectionStatus;} public void setConnectionStatus(String v){connectionStatus=CandidateProfile.safe(v);}
    public String getTermsNotes(){return termsNotes;} public void setTermsNotes(String v){termsNotes=CandidateProfile.safe(v);}
    public String getRateLimitNotes(){return rateLimitNotes;} public void setRateLimitNotes(String v){rateLimitNotes=CandidateProfile.safe(v);}
    public String getLastError(){return lastError;} public void setLastError(String v){lastError=CandidateProfile.safe(v);}
    public LocalDateTime getLastTestAt(){return lastTestAt;} public void setLastTestAt(LocalDateTime v){lastTestAt=v;}
    public LocalDateTime getLastSyncAt(){return lastSyncAt;} public void setLastSyncAt(LocalDateTime v){lastSyncAt=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
}

@Entity
@Table(name="outreach_record")
class OutreachRecord {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=40) private String portal="External";
    @Column(length=180) private String contactName="";
    @Column(length=180) private String company="";
    @Column(length=2048) private String profileUrl="";
    private Long jobId;
    @Column(length=2048) private String jobUrl="";
    @Column(length=8000) private String draftMessage="";
    @Column(length=3000) private String notes="";
    private LocalDate followUpDate;
    @Column(nullable=false, length=20) private String status="DRAFT";
    private LocalDateTime sentAt;
    @Column(length=1000) private String confirmationEvidence="";
    private LocalDateTime createdAt=LocalDateTime.now();
    private LocalDateTime updatedAt=LocalDateTime.now();
    public OutreachRecord(){}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getPortal(){return portal;} public void setPortal(String v){portal=CandidateProfile.safe(v);}
    public String getContactName(){return contactName;} public void setContactName(String v){contactName=CandidateProfile.safe(v);}
    public String getCompany(){return company;} public void setCompany(String v){company=CandidateProfile.safe(v);}
    public String getProfileUrl(){return profileUrl;} public void setProfileUrl(String v){profileUrl=CandidateProfile.safe(v);}
    public Long getJobId(){return jobId;} public void setJobId(Long v){jobId=v;}
    public String getJobUrl(){return jobUrl;} public void setJobUrl(String v){jobUrl=CandidateProfile.safe(v);}
    public String getDraftMessage(){return draftMessage;} public void setDraftMessage(String v){draftMessage=CandidateProfile.safe(v);}
    public String getNotes(){return notes;} public void setNotes(String v){notes=CandidateProfile.safe(v);}
    public LocalDate getFollowUpDate(){return followUpDate;} public void setFollowUpDate(LocalDate v){followUpDate=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=CandidateProfile.safe(v);}
    public LocalDateTime getSentAt(){return sentAt;} public void setSentAt(LocalDateTime v){sentAt=v;}
    public String getConfirmationEvidence(){return confirmationEvidence;} public void setConfirmationEvidence(String v){confirmationEvidence=CandidateProfile.safe(v);}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
