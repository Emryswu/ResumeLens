package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeAgentOverviewServiceTests {
    @Test
    void agentOverviewsUseTheOwnersResumesAndSortByUpdateTimeWithoutChangingNormalLists() {
        var owner = new AppUser();
        owner.setUsername("owner");
        ReflectionTestUtils.setField(owner, "id", 7L);
        var current = mock(CurrentUserService.class);
        when(current.getCurrentUser()).thenReturn(owner);
        var row = new Resume();
        row.setUser(owner);
        row.setTitle("简历 v2");
        row.setRawText("专业技能：Java Spring Boot");
        var repository = mock(ResumeRepository.class);
        when(repository.findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
                eq(7L), eq("Java"), eq(7L), eq("Java"), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(row), invocation.getArgument(4), 1));
        var service = new ResumeService(repository, null, current, null, null, "unused", null, null, null);
        assertEquals("专业技能：Java Spring Boot", service.findAllForAgent(0, 5, " Java ").content().getFirst().rawText());
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
                eq(7L), eq("Java"), eq(7L), eq("Java"), pageable.capture());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("updatedAt").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("id").getDirection());
        assertNull(service.findAll(0, 5, "Java").content().getFirst().rawText());
    }
}
