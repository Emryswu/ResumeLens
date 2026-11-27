package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.ai.AiProperties;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static com.arthur.jdragresume.agent.AgentEvalV3.JSON;

/** Local developer-only run. Never consumes the frozen v3 acceptance slot. */
class LocalChatGptDevEvalTests {
    @Test
    void runOnlyWithExplicitLocalConsent() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_CHATGPT_DEV_EVAL")),
                "No live ChatGPT requests by default");
        String python = required("CHATGPT_PLAN_PYTHON");
        Path helper = Path.of("..", "tools", "chatgpt-plan", "chatgpt_plan.py").toAbsolutePath().normalize();
        String override = System.getenv("CHATGPT_DEV_MODEL");
        String modelId = override == null || override.isBlank()
                ? helperOutput(python, helper, "selected-model") : override;
        AiProperties properties = new AiProperties();
        properties.setModel(modelId);
        var client = AiClient.forLocalChatGpt(properties, JSON, () -> helperOutput(python, helper, "token"));
        var delegate = new OpenAiCompatibleAgentModel(client);
        // Keep the existing development-eval provider default independent of interactive chat preferences.
        AgentModel model = (system, transcript, tools, timeout) -> delegate.next(system, transcript, tools, timeout,
                com.arthur.jdragresume.ai.ReasoningEffort.DEFAULT);
        var group = AgentEvalV3.group("dev");
        String taskId = System.getenv("CHATGPT_DEV_TASK");
        if (taskId != null) group.task(taskId); // fail before inference if the requested task does not exist

        Path dir = Path.of("target", "chatgpt-dev-eval");
        Files.createDirectories(dir);
        Path report = Files.createTempFile(dir, "run-", ".jsonl");
        for (JsonNode task : group.tasks()) {
            String id = task.path("id").asText();
            if (taskId != null && !taskId.equals(id)) continue;
            var trial = AgentEvalV3.run(group, task, scenario -> model);
            var providerFailure = AgentJobSearchEvalV3Tests.providerFailure(trial);
            if (providerFailure.isPresent()) {
                throw new BusinessException(providerFailure.get(),
                        "Provider interrupted development evaluation; completed results remain in " + report);
            }
            ObjectNode row = JSON.createObjectNode();
            row.put("task", id);
            row.put("model", modelId);
            row.put("time", Instant.now().toString());
            row.put("passed", trial.passed());
            row.put("category", trial.category());
            row.set("metrics", JSON.valueToTree(trial.metrics()));
            row.set("checks", JSON.valueToTree(trial.checks()));
            Files.writeString(report, JSON.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.println("ChatGPT dev task " + id + ": " + (trial.passed() ? "PASS" : "FAIL"));
        }
        System.out.println("Development evaluation results: " + report.toAbsolutePath());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new BusinessException("AI_NOT_CONFIGURED", "Set " + name + " for local ChatGPT evaluation");
        }
        return value;
    }

    private static String helperOutput(String python, Path helper, String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(python, helper.toString(), command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                throw new BusinessException("AI_AUTH_FAILED", "Local ChatGPT authorization helper timed out");
            }
            if (process.exitValue() != 0) {
                throw new BusinessException("AI_AUTH_FAILED", "Authorize ChatGPT and select a model in local settings");
            }
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
                throw new BusinessException("AI_AUTH_FAILED", "Invalid response from local authorization helper");
            }
            return value;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI_REQUEST_INTERRUPTED", "Local ChatGPT authorization interrupted");
        } catch (IOException ex) {
            throw new BusinessException("AI_AUTH_FAILED", "Could not start local ChatGPT authorization helper");
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
