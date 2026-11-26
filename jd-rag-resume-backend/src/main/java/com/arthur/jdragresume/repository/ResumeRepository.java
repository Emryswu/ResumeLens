package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.Resume;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    @Query("select resume.id from Resume resume")
    Set<Long> findAllIds();

    @Query("select resume.id from Resume resume where resume.id in :ids")
    Set<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    long countByUserId(Long userId);

    @Query("select coalesce(sum(resume.fileSize), 0) from Resume resume where resume.user.id = :userId")
    long sumFileSizeByUserId(@Param("userId") Long userId);

    Page<Resume> findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
            Long userIdForTitle,
            String titleKeyword,
            Long userIdForCandidateName,
            String candidateNameKeyword,
            Pageable pageable
    );
}
