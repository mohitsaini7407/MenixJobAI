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
interface JobSourceConnectionRepository extends JpaRepository<JobSourceConnection, Long> {
    Optional<JobSourceConnection> findBySourceUrl(String sourceUrl);
}
interface OutreachRepository extends JpaRepository<OutreachRecord, Long> {}
