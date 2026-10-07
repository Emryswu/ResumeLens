package com.arthur.jdragresume.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
