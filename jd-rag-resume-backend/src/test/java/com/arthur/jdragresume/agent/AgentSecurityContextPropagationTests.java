package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every tool goes through services that read the user from SecurityContextHolder. The agent
 * loop runs on a pool thread, so the executor bean must carry the submitting request's
 * identity across, and keep it even after the request thread has cleared its own context.
 */
class AgentSecurityContextPropagationTests {
    private final ThreadPoolTaskExecutor pool = new AgentConfig().agentThreadPool();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        pool.shutdown();
    }

    @Test
    void poolThreadSeesTheSubmittingUser() throws Exception {
        TaskExecutor executor = new AgentConfig().agentTaskExecutor(pool);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
        CountDownLatch requestThreadFinished = new CountDownLatch(1);
        CompletableFuture<String> seen = new CompletableFuture<>();

        executor.execute(() -> {
            try {
                requestThreadFinished.await(5, TimeUnit.SECONDS);
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                seen.complete(authentication == null ? "<none>" : authentication.getName());
            } catch (Exception ex) {
                seen.completeExceptionally(ex);
            }
        });
        // What SecurityContextHolderFilter does when the request thread returns the emitter.
        SecurityContextHolder.clearContext();
        requestThreadFinished.countDown();

        assertEquals("alice", seen.get(5, TimeUnit.SECONDS));
    }

    @Test
    void virtualThreadExecutorRunsOnVirtualThreadAndPropagatesSecurityContext() throws Exception {
        AgentConfig config = new AgentConfig();
        AgentProperties properties = new AgentProperties();
        properties.setVirtualThreadsEnabled(true);
        properties.setMaxConcurrentTurns(4);
        properties.setMaxQueuedTurns(4);
        TaskExecutor executor = config.agentTaskExecutor(properties, pool);
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("bob", null, List.of()));
            CountDownLatch requestThreadFinished = new CountDownLatch(1);
            CompletableFuture<String> userSeen = new CompletableFuture<>();
            CompletableFuture<Boolean> isVirtual = new CompletableFuture<>();
            CompletableFuture<String> threadName = new CompletableFuture<>();

            executor.execute(() -> {
                try {
                    requestThreadFinished.await(5, TimeUnit.SECONDS);
                    isVirtual.complete(Thread.currentThread().isVirtual());
                    threadName.complete(Thread.currentThread().getName());
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    userSeen.complete(authentication == null ? "<none>" : authentication.getName());
                } catch (Exception ex) {
                    userSeen.completeExceptionally(ex);
                }
            });
            SecurityContextHolder.clearContext();
            requestThreadFinished.countDown();

            assertTrue(isVirtual.get(5, TimeUnit.SECONDS), "task must execute on a Java 21 virtual thread");
            assertTrue(threadName.get(5, TimeUnit.SECONDS).startsWith("agent-vt-"));
            assertEquals("bob", userSeen.get(5, TimeUnit.SECONDS));
        } finally {
            config.shutdownVirtualExecutor();
        }
    }

    @Test
    void virtualThreadExecutorRejectsOverCapacityWithTaskRejectedExceptionAndReturns503AgentBusy() throws Exception {
        AgentConfig config = new AgentConfig();
        AgentProperties properties = new AgentProperties();
        properties.setVirtualThreadsEnabled(true);
        properties.setMaxConcurrentTurns(1);
        properties.setMaxQueuedTurns(1);
        TaskExecutor executor = config.agentTaskExecutor(properties, pool);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                running.countDown();
                try {
                    releaseFirst.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));

            // Second task fills the 1-slot queue while the first holds the 1 concurrency permit.
            executor.execute(queuedDone::countDown);

            assertThrows(TaskRejectedException.class, () -> executor.execute(() -> {}));

            // Verify AgentChatService maps TaskRejectedException to 503 AGENT_BUSY and fails the lease.
            AppUser user = new AppUser();
            ReflectionTestUtils.setField(user, "id", 1L);
            CurrentUserService currentUserService = mock(CurrentUserService.class);
            when(currentUserService.getCurrentUser()).thenReturn(user);
            ConversationStore conversations = mock(ConversationStore.class);
            var run = new ConversationStore.Run(1L, 9L, "turn-1", "run-1", null, null, 0L);
            when(conversations.accept(eq(1L), eq(9L), eq("你好"), any(), any(), anyLong(), any())).thenReturn(run);
            AgentLoop loop = mock(AgentLoop.class);
            AgentToolRegistry registry = new AgentToolRegistry(List.of());
            AgentChatService chatService = new AgentChatService(
                    currentUserService,
                    new TranscriptPolicy(properties),
                    loop,
                    new SlidingWindowRateLimiter(),
                    executor,
                    properties,
                    mock(AgentMemoryService.class),
                    registry,
                    new BrowserTaskRegistry(conversations),
                    conversations
            );

            BusinessException busy = assertThrows(BusinessException.class,
                    () -> chatService.chat(9L, "你好", null, null, false, null, null));
            assertEquals("AGENT_BUSY", busy.getCode());
            verify(conversations).fail(eq(run), eq("AGENT_BUSY"), any());

            releaseFirst.countDown();
            assertTrue(queuedDone.await(5, TimeUnit.SECONDS));

            // Capacity recovers once active and queued tasks finish.
            AtomicBoolean recovered = new AtomicBoolean(false);
            CountDownLatch done = new CountDownLatch(1);
            executor.execute(() -> {
                recovered.set(Thread.currentThread().isVirtual());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(recovered.get());
        } finally {
            releaseFirst.countDown();
            config.shutdownVirtualExecutor();
        }
    }
}
