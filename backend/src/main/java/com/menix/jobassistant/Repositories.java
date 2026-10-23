package com.menix.jobassistant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

interface ProfileRepository extends JpaRepository<CandidateProfile, Long> {}
interface JobRepository extends JpaRepository<JobListing, Long> {
    Optional<JobListing> findByUrl(String url);
    Optional<JobListing> findFirstBySourceIgnoreCaseAndSourceJobIdIgnoreCase(String source, String sourceJobId);
}
interface ApplicationRepository extends JpaRepository<JobApplication, Long> {
    boolean existsByJobId(Long jobId);
    Optional<JobApplication> findByJobId(Long jobId);
}
interface AutofillSessionRepository extends JpaRepository<AutofillSession, Long> {}
interface SmartApplyAnswerRepository extends JpaRepository<SmartApplyAnswer, Long> {
    Optional<SmartApplyAnswer> findByQuestionKey(String questionKey);
}
interface SmartApplyPreferencesRepository extends JpaRepository<SmartApplyPreferences, Long> {}
interface DailySyncSettingsRepository extends JpaRepository<DailySyncSettings, Long> {}
interface JobSourceConnectionRepository extends JpaRepository<JobSourceConnection, Long> {
    Optional<JobSourceConnection> findBySourceUrl(String sourceUrl);
}
interface JobSourceSyncStateRepository extends JpaRepository<JobSourceSyncState, String> {}
interface CompanyBoardConfigurationRepository extends JpaRepository<CompanyBoardConfiguration, Long> {
    List<CompanyBoardConfiguration> findByProviderIgnoreCase(String provider);
    List<CompanyBoardConfiguration> findByProviderIgnoreCaseAndEnabledTrue(String provider);
    long countByProviderIgnoreCase(String provider);
}
interface AdzunaSearchProfileRepository extends JpaRepository<AdzunaSearchProfile, Long> {}
interface AdzunaDailyApiUsageRepository extends JpaRepository<AdzunaDailyApiUsage, java.time.LocalDate> {}
interface EmailAlertSettingsRepository extends JpaRepository<EmailAlertSettings, Long> {}
interface EmailAlertProcessedMessageRepository extends JpaRepository<EmailAlertProcessedMessage, String> {}
interface OutreachRepository extends JpaRepository<OutreachRecord, Long> {}
