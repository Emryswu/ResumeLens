package com.arthur.jdragresume.agent.tool;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolEmptyResultPolicyTests {
    @Test
    void everyRegisteredToolExplicitlyDeclaresWhetherEmptyResultsAreValid() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentTool.class));

        var tools = new ArrayList<AgentTool>();
        for (var candidate : scanner.findCandidateComponents("com.arthur.jdragresume.agent.tool")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            assertEquals(type, type.getDeclaredMethod("emptyResultIsValid").getDeclaringClass(),
                    type.getSimpleName() + " must declare its own empty-result policy");
            tools.add((AgentTool) Mockito.mock(type, Mockito.CALLS_REAL_METHODS));
        }

        var registry = new AgentToolRegistry(tools);
        assertFalse(registry.names().isEmpty(), "the tool registry scan must discover real tools");
        assertEquals(tools.size(), registry.names().size());
        assertTrue(registry.names().contains("list_job_applications"));
        assertTrue(registry.names().contains("search_jobs"));
        assertTrue(registry.names().contains("rank_jobs_for_resume"));
        assertTrue(registry.find("list_job_applications").orElseThrow().emptyResultIsValid());
        assertTrue(registry.find("list_resumes").orElseThrow().emptyResultIsValid());
        assertFalse(registry.find("search_jobs").orElseThrow().emptyResultIsValid());
        assertFalse(registry.find("rank_jobs_for_resume").orElseThrow().emptyResultIsValid());
        for (String name : registry.names()) {
            assertTrue(registry.find(name).isPresent(), name);
        }
    }
}
