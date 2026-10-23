package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobApplicationServiceTests {
    private JdbcTemplate jdbc;
    private JobDescriptionRepository jobs;
    private CurrentUserService currentUser;
    private JobApplicationService service;

    @BeforeEach
    void init() {
        jdbc = mock(JdbcTemplate.class);
        jobs = mock(JobDescriptionRepository.class);
        currentUser = mock(CurrentUserService.class);
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 7L);
        when(currentUser.getCurrentUser()).thenReturn(user);
        JobDescription job = new JobDescription();
        ReflectionTestUtils.setField(job, "id", 21L);
        job.setTitle("Java 实习生");
        job.setCompanyName("星河");
        when(jobs.findByIdAndUserId(21L, 7L)).thenReturn(Optional.of(job));
        service = new JobApplicationService(jdbc, jobs, currentUser);
    }

    @Test
    void nonexistentOrForeignJobCannotBeReadWrittenOrReset() {
        assertThrows(ResourceNotFoundException.class, () -> service.get(99));
        assertThrows(ResourceNotFoundException.class, () -> service.save(99,
                new JobApplicationService.Update(JobApplicationService.Status.APPLIED, "已投")));
        assertThrows(ResourceNotFoundException.class, () -> service.clear(99));
        verifyNoInteractions(jdbc);
        verify(jobs, times(3)).findByIdAndUserId(99L, 7L);
    }

    @Test
    void savedFeedbackIsScopedToTheUserAndFreeTextIsTrimmed() {
        doReturn(java.util.List.of()).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
        service.save(21, new JobApplicationService.Update(JobApplicationService.Status.NOT_INTERESTED, "  要求到岗六天  "));
        verify(jdbc).update(contains("ON DUPLICATE KEY UPDATE"), eq(7L), eq(21L),
                eq("NOT_INTERESTED"), eq("要求到岗六天"));
        verify(jobs, atLeastOnce()).findByIdAndUserId(21L, 7L);
    }

    @Test
    void untrackedAndClearedAreDistinctFromUserRejection() {
        doReturn(java.util.List.of()).when(jdbc).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any());
        assertEquals("UNTRACKED", service.get(21).status());
        service.clear(21);
        verify(jdbc).update(startsWith("DELETE FROM job_application"), eq(7L), eq(21L));
    }
}
