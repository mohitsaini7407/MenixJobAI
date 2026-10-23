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
@Table(name="daily_sync_settings")
class DailySyncSettings {
    @Id private Long id = 1L;
    private boolean enabled;
    private String syncTime = "08:15";
    private boolean runOnOpen;
    private boolean companyBoardsEnabled = true;
    private boolean adzunaEnabled = true;
    private boolean emailAlertsEnabled = true;
    private boolean careerPagesEnabled = true;
    private LocalDate lastRunDate;
    private Instant lastRunAt;
    @Column(length=1000) private String lastError = "";
    public Long getId(){return id;} public void setId(Long value){id=value;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
    public String getSyncTime(){return syncTime;} public void setSyncTime(String value){syncTime=CandidateProfile.safe(value);}
    public boolean isRunOnOpen(){return runOnOpen;} public void setRunOnOpen(boolean value){runOnOpen=value;}
    public boolean isCompanyBoardsEnabled(){return companyBoardsEnabled;} public void setCompanyBoardsEnabled(boolean value){companyBoardsEnabled=value;}
    public boolean isAdzunaEnabled(){return adzunaEnabled;} public void setAdzunaEnabled(boolean value){adzunaEnabled=value;}
    public boolean isEmailAlertsEnabled(){return emailAlertsEnabled;} public void setEmailAlertsEnabled(boolean value){emailAlertsEnabled=value;}
    public boolean isCareerPagesEnabled(){return careerPagesEnabled;} public void setCareerPagesEnabled(boolean value){careerPagesEnabled=value;}
    public LocalDate getLastRunDate(){return lastRunDate;} public void setLastRunDate(LocalDate value){lastRunDate=value;}
    public Instant getLastRunAt(){return lastRunAt;} public void setLastRunAt(Instant value){lastRunAt=value;}
    public String getLastError(){return lastError;} public void setLastError(String value){lastError=CandidateProfile.safe(value);}
}

@Entity
@Table(name="job_listing")
class JobListing {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private String title = ""; private String company = ""; private String location = "";
    private String url = ""; private String applyUrl = ""; private String source = "External Link"; private String employmentType = "";
    private String experienceLevel = ""; @Column(length=8000) private String description = "";
    @Column(length=2000) private String requiredSkills = "";
    private LocalDate publishedAt; private LocalDateTime retrievedAt = LocalDateTime.now();
    private Instant postedAt; private Boolean exactPostingTime; private String workMode = "";
    private Instant validThrough;
    @Column(nullable=false, columnDefinition="boolean default false") private boolean applicationUrlVerified;
    @Column(length=2000) private String experienceRequirements = "";
    private String salary = ""; private String sourceJobId = ""; private String attributionUrl = "";
    @Column(length=40) private String descriptionStatus = "full";
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
    public Instant getValidThrough(){return validThrough;} public void setValidThrough(Instant v){validThrough=v;}
    public boolean isExactPostingTime(){return Boolean.TRUE.equals(exactPostingTime);} public void setExactPostingTime(Boolean v){exactPostingTime=v;}
    public String getWorkMode(){return workMode;} public void setWorkMode(String v){workMode=CandidateProfile.safe(v);}
    public boolean isApplicationUrlVerified(){return applicationUrlVerified;} public void setApplicationUrlVerified(boolean v){applicationUrlVerified=v;}
    public String getExperienceRequirements(){return experienceRequirements;} public void setExperienceRequirements(String v){experienceRequirements=CandidateProfile.safe(v);}
    public String getSalary(){return salary;} public void setSalary(String v){salary=CandidateProfile.safe(v);}
    public String getDescriptionStatus(){return descriptionStatus==null||descriptionStatus.isBlank()?"unknown":descriptionStatus;} public void setDescriptionStatus(String v){descriptionStatus=CandidateProfile.safe(v);}
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
@Table(name="company_board_configuration", uniqueConstraints=@UniqueConstraint(columnNames={"provider","identifier"}))
class CompanyBoardConfiguration {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=32) private String provider="";
    @Column(nullable=false, length=100) private String identifier="";
    @Column(nullable=false, length=180) private String company="";
    @Column(nullable=false) private boolean enabled;
    @Column(nullable=false, length=20) private String testStatus="UNVERIFIED";
    private Integer lastTestJobCount;
    private Instant lastTestAt;
    @Column(length=1000) private String lastError="";
    private LocalDateTime createdAt=LocalDateTime.now();
    private LocalDateTime updatedAt=LocalDateTime.now();
    public CompanyBoardConfiguration(){}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getProvider(){return provider;} public void setProvider(String v){provider=CandidateProfile.safe(v);}
    public String getIdentifier(){return identifier;} public void setIdentifier(String v){identifier=CandidateProfile.safe(v);}
    public String getCompany(){return company;} public void setCompany(String v){company=CandidateProfile.safe(v);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public String getTestStatus(){return testStatus;} public void setTestStatus(String v){testStatus=CandidateProfile.safe(v);}
    public Integer getLastTestJobCount(){return lastTestJobCount;} public void setLastTestJobCount(Integer v){lastTestJobCount=v;}
    public Instant getLastTestAt(){return lastTestAt;} public void setLastTestAt(Instant v){lastTestAt=v;}
    public String getLastError(){return lastError;} public void setLastError(String v){lastError=CandidateProfile.safe(v);}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}

@Entity
@Table(name="adzuna_search_profile")
class AdzunaSearchProfile {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="search_keyword", nullable=false, length=200) private String what="";
    @Column(name="search_location", nullable=false, length=200) private String where="";
    public AdzunaSearchProfile(){}
    public AdzunaSearchProfile(String what,String where){this.what=CandidateProfile.safe(what);this.where=CandidateProfile.safe(where);}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getWhat(){return what;} public void setWhat(String v){what=CandidateProfile.safe(v);}
    public String getWhere(){return where;} public void setWhere(String v){where=CandidateProfile.safe(v);}
}

@Entity
@Table(name="adzuna_daily_api_usage")
class AdzunaDailyApiUsage {
    @Id private LocalDate usageDate;
    @Column(nullable=false) private int calls;
    public AdzunaDailyApiUsage(){}
    public AdzunaDailyApiUsage(LocalDate usageDate){this.usageDate=usageDate;}
    public LocalDate getUsageDate(){return usageDate;} public void setUsageDate(LocalDate v){usageDate=v;}
    public int getCalls(){return calls;} public void setCalls(int v){calls=v;}
}

@Entity
@Table(name="email_alert_settings")
class EmailAlertSettings {
    @Id private Long id=1L;
    @Column(nullable=false, length=255) private String imapHost="imap.gmail.com";
    private int imapPort=993;
    @Column(nullable=false, length=320) private String emailAddress="";
    @Column(nullable=false, length=512) private String encryptedPassword="";
    @Column(nullable=false, length=255) private String folderName="INBOX";
    @Column(nullable=false) private boolean enabled;
    @Column(nullable=false) private boolean markSeen;
    @Column(nullable=false, length=8000) private String allowlistJson="";
    private Instant lastRunAt;
    private Instant lastSuccessfulSyncAt;
    private Integer lastImported=0;
    @Column(length=2000) private String lastError="";
    public EmailAlertSettings(){}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getImapHost(){return imapHost;} public void setImapHost(String v){imapHost=CandidateProfile.safe(v);}
    public int getImapPort(){return imapPort;} public void setImapPort(int v){imapPort=v;}
    public String getEmailAddress(){return emailAddress;} public void setEmailAddress(String v){emailAddress=CandidateProfile.safe(v);}
    public String getEncryptedPassword(){return encryptedPassword;} public void setEncryptedPassword(String v){encryptedPassword=CandidateProfile.safe(v);}
    public String getFolderName(){return folderName;} public void setFolderName(String v){folderName=CandidateProfile.safe(v);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public boolean isMarkSeen(){return markSeen;} public void setMarkSeen(boolean v){markSeen=v;}
    public String getAllowlistJson(){return allowlistJson;} public void setAllowlistJson(String v){allowlistJson=CandidateProfile.safe(v);}
    public Instant getLastRunAt(){return lastRunAt;} public void setLastRunAt(Instant v){lastRunAt=v;}
    public Instant getLastSuccessfulSyncAt(){return lastSuccessfulSyncAt;} public void setLastSuccessfulSyncAt(Instant v){lastSuccessfulSyncAt=v;}
    public Integer getLastImported(){return lastImported;} public void setLastImported(Integer v){lastImported=v;}
    public String getLastError(){return lastError;} public void setLastError(String v){lastError=CandidateProfile.safe(v);}
}

@Entity
@Table(name="email_alert_processed_message")
class EmailAlertProcessedMessage {
    @Id @Column(length=500) private String messageId;
    private Instant processedAt=Instant.now();
    private Integer importedJobs=0;
    public EmailAlertProcessedMessage(){}
    public EmailAlertProcessedMessage(String messageId,int importedJobs){this.messageId=CandidateProfile.safe(messageId);this.importedJobs=importedJobs;}
    public String getMessageId(){return messageId;} public void setMessageId(String v){messageId=CandidateProfile.safe(v);}
    public Instant getProcessedAt(){return processedAt;} public void setProcessedAt(Instant v){processedAt=v;}
    public Integer getImportedJobs(){return importedJobs;} public void setImportedJobs(Integer v){importedJobs=v;}
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
    private Instant lastSuccessfulSyncAt;
    private Integer lastFetched=0;
    private Integer lastImported=0;
    private Integer lastUpdated=0;
    private Integer lastRejected=0;
    private Integer lastDeduplicated=0;
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
    public Instant getLastSuccessfulSyncAt(){return lastSuccessfulSyncAt;} public void setLastSuccessfulSyncAt(Instant v){lastSuccessfulSyncAt=v;}
    public Integer getLastFetched(){return lastFetched;} public void setLastFetched(Integer v){lastFetched=v;}
    public Integer getLastImported(){return lastImported;} public void setLastImported(Integer v){lastImported=v;}
    public Integer getLastUpdated(){return lastUpdated;} public void setLastUpdated(Integer v){lastUpdated=v;}
    public Integer getLastRejected(){return lastRejected;} public void setLastRejected(Integer v){lastRejected=v;}
    public Integer getLastDeduplicated(){return lastDeduplicated;} public void setLastDeduplicated(Integer v){lastDeduplicated=v;}
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
