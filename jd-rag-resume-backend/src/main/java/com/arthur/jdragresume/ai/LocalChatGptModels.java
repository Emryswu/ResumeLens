package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Opt-in, loopback-only integration for one explicitly configured ResumeLens user. */
@Component
public class LocalChatGptModels {
    private final ObjectMapper mapper;
    private final AiProperties defaults;
    private final long owner;
    private final String python;
    private final String script;
    private final String address;

    public LocalChatGptModels(ObjectMapper mapper, AiProperties defaults,
                              @Value("${app.chatgpt.owner-id:0}") long owner,
                              @Value("${app.chatgpt.python:}") String python,
                              @Value("${app.chatgpt.helper:../tools/chatgpt-plan/chatgpt_plan.py}") String script,
                              @Value("${server.address:}") String address) {
        this.mapper = mapper;
        this.defaults = defaults;
        this.owner = owner;
        this.python = python;
        this.script = Path.of(script).toAbsolutePath().normalize().toString();
        this.address = address;
    }

    public record Model(String slug, String name) {}
    public record Catalog(boolean enabled, boolean connected, List<Model> models) {}

    public boolean enabled(long userId) {
        return owner > 0 && owner == userId && "127.0.0.1".equals(address)
                && !python.isBlank() && !defaults.isMockEnabled();
    }

    public Catalog catalog(long userId) {
        if (!enabled(userId)) return new Catalog(false, false, List.of());
        try {
            JsonNode data = mapper.readTree(command("models-json"));
            List<Model> models = new ArrayList<>();
            for (JsonNode item : data.required("models")) {
                models.add(new Model(item.required("slug").asText(), item.required("name").asText()));
            }
            return new Catalog(true, data.required("connected").asBoolean(), List.copyOf(models));
        } catch (IOException | IllegalArgumentException ex) {
            throw new BusinessException("AI_AUTH_FAILED", "Invalid response from local authorization helper");
        }
    }

    public void select(long userId, String slug) {
        requireOwner(userId);
        requireSlug(slug);
        // The helper validates the slug against the account's current catalog before saving.
        command("select-model", slug);
    }

    public AiClient client(long userId, String slug) {
        requireOwner(userId);
        requireSlug(slug);
        Catalog catalog = catalog(userId);
        if (!catalog.connected() || catalog.models().stream().noneMatch(model -> model.slug().equals(slug))) {
            throw new BusinessException("AI_NOT_CONFIGURED", "Authorize ChatGPT and choose an available model");
        }
        AiProperties config = new AiProperties();
        config.setModel(slug);
        config.setTimeoutSeconds(defaults.getTimeoutSeconds());
        return AiClient.forLocalChatGpt(config, mapper, () -> token(userId));
    }

    private String token(long userId) {
        requireOwner(userId);
        String value = command("token");
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException("AI_AUTH_FAILED", "Invalid local authorization response");
        }
        return value;
    }

    private void requireOwner(long userId) {
        if (!enabled(userId)) {
            throw new BusinessException("AI_NOT_CONFIGURED", "Local ChatGPT authorization is not enabled for this user");
        }
    }

    private static void requireSlug(String slug) {
        // External model IDs are passed as a positional argument to the local helper.
        if (slug == null || !slug.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new BusinessException("AI_NOT_CONFIGURED", "Invalid ChatGPT model");
        }
    }

    private String command(String... args) {
        List<String> command = new ArrayList<>(List.of(python, script));
        command.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process running = process;
            // Read concurrently: a model catalog can exceed the OS pipe buffer.
            FutureTask<byte[]> output = new FutureTask<>(() -> {
                try (var stream = running.getInputStream()) {
                    return stream.readNBytes(1024 * 1024 + 1);
                }
            });
            Thread.ofVirtual().start(output);
            if (!process.waitFor(40, TimeUnit.SECONDS)) {
                throw new BusinessException("AI_AUTH_FAILED", "Local ChatGPT authorization helper timed out");
            }
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 1024 * 1024) {
                throw new BusinessException("AI_AUTH_FAILED", "Open local ChatGPT settings and authorize again");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI_REQUEST_INTERRUPTED", "Local ChatGPT authorization interrupted");
        } catch (Exception ex) {
            throw new BusinessException("AI_AUTH_FAILED", "Could not run local ChatGPT authorization helper");
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
