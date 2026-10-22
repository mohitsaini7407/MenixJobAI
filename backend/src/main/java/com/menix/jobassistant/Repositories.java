package com.menix.jobassistant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface ProfileRepository extends JpaRepository<CandidateProfile, Long> {}
interface JobRepository extends JpaRepository<JobListing, Long> {
    Optional<JobListing> findByUrl(String url);
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
interface JobSourceConnectionRepository extends JpaRepository<JobSourceConnection, Long> {
    Optional<JobSourceConnection> findBySourceUrl(String sourceUrl);
}
interface JobSourceSyncStateRepository extends JpaRepository<JobSourceSyncState, String> {}
interface OutreachRepository extends JpaRepository<OutreachRecord, Long> {}
