package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.controller.AgentMemoryController;
import com.arthur.jdragresume.entity.AgentMemory;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AgentMemoryRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentMemoryTests {
    private final AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final AgentMemorySearch search = new AgentMemorySearch();
    private final AppUser alice = user(1L);
    private final AppUser bob = user(2L);
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        when(currentUser.getCurrentUser()).thenReturn(alice);
        when(repository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of());
        when(repository.save(any(AgentMemory.class))).thenAnswer(invocation -> {
            AgentMemory memory = invocation.getArgument(0);
            ReflectionTestUtils.setField(memory, "id", 10L);
            return memory;
        });
        service = new AgentMemoryService(repository, currentUser, search, new AgentProperties());
    }

    @Test
    void onlyExplicitFirstPersonDeclarationsAreStoredVerbatim() {
        var recalled = service.rememberAndRecall("我的目标城市是上海，我的求职方向是Java后端，我自认的短板是算法", true);
        assertEquals(3, recalled.size());
        verify(repository, times(3)).save(argThat(memory -> memory.getUser() == alice
                && List.of("我的目标城市是上海", "我的求职方向是Java后端", "我自认的短板是算法").contains(memory.getContent())));
    }

    @Test
    void questionsHypotheticalsQuotesAndImpliedTraitsDoNotBecomeProfiles() {
        for (String input : List.of("上海有哪些公司", "帮我看看上海的Java岗位", "如果我的目标城市是上海", "比如我的目标城市是上海",
                "他说我的目标城市是上海", "我的目标城市是上海吗？", "我的目标城市是上海，不要记住", "“我的目标城市是上海”",
                "简历写了三年Java，所以我擅长Java", "我想做一次分析", "我的目标城市不是上海")) {
            assertTrue(service.rememberAndRecall(input, true).isEmpty(), input);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void approvalRequestsRecallWithoutPersistingTheOldUserMessageAgain() {
        service.rememberAndRecall("我的目标城市是上海", false);
        verify(repository, never()).save(any());
    }

    @Test
    void repeatedDeclarationsAreNotDuplicated() {
        when(repository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(memory(11L, alice, "我的目标城市是上海")));
        assertEquals(1, service.rememberAndRecall("我的目标城市是上海", true).size());
        verify(repository, never()).save(any());
    }

    @Test
    void recallFiltersByUserEvenIfTheIndexReceivesMixedUsers() {
        var foreign = memory(21L, bob, "我的目标城市是上海");
        var own = memory(11L, alice, "我的目标城市是上海");
        assertEquals(List.of(own), search.search(1L, "上海工作", List.of(foreign, own), 5));
        assertEquals(List.of(foreign), search.search(2L, "上海工作", List.of(foreign, own), 5));
        assertTrue(search.search(1L, "北京", List.of(own), 5).isEmpty());
        assertEquals(List.of(own), search.search(1L, "这周优先准备哪几家", List.of(foreign, own), 5));
    }

    @Test
    void listAndDeleteAreScopedToTheAuthenticatedUser() throws Exception {
        var own = memory(11L, alice, "我的目标城市是上海");
        when(repository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(own));
        when(repository.findByIdAndUserId(11L, 1L)).thenReturn(Optional.of(own));
        mvc().perform(get("/api/agent/memories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(11))
                .andExpect(jsonPath("$.data[0].content").value("我的目标城市是上海"));
        mvc().perform(delete("/api/agent/memories/11")).andExpect(status().isNoContent());
        verify(repository).delete(own);
        when(currentUser.getCurrentUser()).thenReturn(bob);
        mvc().perform(delete("/api/agent/memories/11")).andExpect(status().isNotFound());
        verify(repository, times(1)).delete(any());
    }

    @Test
    void deletionHasNoStaleLuceneCopyOnTheNextRecall() {
        var own = memory(11L, alice, "我的目标城市是上海");
        when(repository.findByUserIdOrderByIdDesc(1L)).thenReturn(List.of(own), List.of());
        when(repository.findByIdAndUserId(11L, 1L)).thenReturn(Optional.of(own));
        assertEquals(1, service.rememberAndRecall("上海", false).size());
        service.delete(11L);
        assertTrue(service.rememberAndRecall("上海", false).isEmpty());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AgentMemoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static AppUser user(Long id) {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static AgentMemory memory(Long id, AppUser user, String text) {
        AgentMemory memory = new AgentMemory();
        ReflectionTestUtils.setField(memory, "id", id);
        memory.setUser(user);
        memory.setCategory("target_city");
        memory.setContent(text);
        return memory;
    }
}
