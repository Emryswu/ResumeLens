package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.JobDescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {
    @Query("""
            select j from JobDescription j where j.user.id = :userId
            and (:employmentType = '' or lower(j.employmentType) = lower(:employmentType))
            and (:keyword = '' or locate(lower(:keyword), lower(j.title)) > 0
                or locate(lower(:keyword), lower(j.companyName)) > 0)
            """)
    Page<JobDescription> searchForUser(@Param("userId") Long userId, @Param("keyword") String keyword,
                                       @Param("employmentType") String employmentType, Pageable pageable);

    interface TypeCount {
        String getEmploymentType();
        long getTotal();
    }

    @Query("""
            select coalesce(nullif(trim(j.employmentType), ''), '未标注') as employmentType, count(j) as total
            from JobDescription j where j.user.id = :userId
            group by coalesce(nullif(trim(j.employmentType), ''), '未标注')
            """)
    List<TypeCount> countTypesForUser(@Param("userId") Long userId);

    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);

    Optional<JobDescription> findByUserIdAndSourcePlatformAndSourceJobId(
            Long userId,
            String sourcePlatform,
            String sourceJobId
    );

    long countByUserId(Long userId);

    List<JobDescription> findAllByUserId(Long userId);

    Page<JobDescription> findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCompanyNameContainingIgnoreCase(
            Long userIdForTitle,
            String titleKeyword,
            Long userIdForCompanyName,
            String companyNameKeyword,
            Pageable pageable
    );
}
