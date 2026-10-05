package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.ai.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

@Configuration
public class AgentConfig {
    @Bean
    public AgentModel agentModel(AiProperties aiProperties, AiClient aiClient, ObjectMapper objectMapper) {
        return aiProperties.isMockEnabled()
                ? new ScriptedAgentModel(objectMapper)
                : new OpenAiCompatibleAgentModel(aiClient);
    }

    @Bean
    public TranscriptPolicy transcriptPolicy(AgentProperties properties) {
        return new TranscriptPolicy(properties);
    }

    @Bean
    public AgentLoop agentLoop(
            AgentModel agentModel,
            AgentToolRegistry registry,
            ObjectMapper objectMapper,
            AgentProperties properties
    ) {
        return new AgentLoop(agentModel, registry, objectMapper, properties, System::currentTimeMillis);
    }

    /** Small and bounded: each turn holds a thread for up to the turn budget while waiting on the model. */
    @Bean(name = "agentThreadPool")
    public ThreadPoolTaskExecutor agentThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("agent-");
        executor.initialize();
        return executor;
    }

    /**
     * Every service resolves the user from SecurityContextHolder, a ThreadLocal that a pool
     * thread does not have. The wrapper captures the submitting request's context object at
     * execute() time and installs it around the task; the request thread later clearing its
     * own ThreadLocal does not touch the captured object.
     */
    @Bean(name = "agentTaskExecutor")
    public TaskExecutor agentTaskExecutor(@Qualifier("agentThreadPool") ThreadPoolTaskExecutor agentThreadPool) {
        return new DelegatingSecurityContextAsyncTaskExecutor(agentThreadPool);
    }
}
