package com.arthur.jdragresume.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {
    /** Model calls per user turn; each call may request several tools. */
    private int maxSteps = 6;
    /** Wall-clock budget for one turn. Checked between steps: a running tool is not interrupted. */
    private long turnTimeoutSeconds = 90;
    /** Upper bound for a single model call; the effective timeout is min(this, remaining turn budget). */
    private long modelCallTimeoutSeconds = 60;
    /** Must outlive the turn budget, otherwise Tomcat's 30s default async timeout cuts the stream. */
    private long emitterTimeoutMs = 120_000;
    private int toolResultMaxChars = 4000;
    private int resultPreviewChars = 200;
    /** Tool results from earlier turns are shortened to this many characters before reaching the model. */
    private int compactedToolResultChars = 500;
    private int maxMessages = 80;
    private int maxTranscriptChars = 60_000;
    /** Hard request ceiling; anything between this and the budget above is trimmed, not rejected. */
    private int maxRequestChars = 200_000;
    private int maxUserMessageChars = 4000;
    private int maxTurnsPerWindow = 20;
    private long windowMinutes = 10;

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public long getTurnTimeoutSeconds() {
        return turnTimeoutSeconds;
    }

    public void setTurnTimeoutSeconds(long turnTimeoutSeconds) {
        this.turnTimeoutSeconds = turnTimeoutSeconds;
    }

    public long getModelCallTimeoutSeconds() {
        return modelCallTimeoutSeconds;
    }

    public void setModelCallTimeoutSeconds(long modelCallTimeoutSeconds) {
        this.modelCallTimeoutSeconds = modelCallTimeoutSeconds;
    }

    public long getEmitterTimeoutMs() {
        return emitterTimeoutMs;
    }

    public void setEmitterTimeoutMs(long emitterTimeoutMs) {
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    public int getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    public void setToolResultMaxChars(int toolResultMaxChars) {
        this.toolResultMaxChars = toolResultMaxChars;
    }

    public int getResultPreviewChars() {
        return resultPreviewChars;
    }

    public void setResultPreviewChars(int resultPreviewChars) {
        this.resultPreviewChars = resultPreviewChars;
    }

    public int getCompactedToolResultChars() {
        return compactedToolResultChars;
    }

    public void setCompactedToolResultChars(int compactedToolResultChars) {
        this.compactedToolResultChars = compactedToolResultChars;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public int getMaxTranscriptChars() {
        return maxTranscriptChars;
    }

    public void setMaxTranscriptChars(int maxTranscriptChars) {
        this.maxTranscriptChars = maxTranscriptChars;
    }

    public int getMaxRequestChars() {
        return maxRequestChars;
    }

    public void setMaxRequestChars(int maxRequestChars) {
        this.maxRequestChars = maxRequestChars;
    }

    public int getMaxUserMessageChars() {
        return maxUserMessageChars;
    }

    public void setMaxUserMessageChars(int maxUserMessageChars) {
        this.maxUserMessageChars = maxUserMessageChars;
    }

    public int getMaxTurnsPerWindow() {
        return maxTurnsPerWindow;
    }

    public void setMaxTurnsPerWindow(int maxTurnsPerWindow) {
        this.maxTurnsPerWindow = maxTurnsPerWindow;
    }

    public long getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(long windowMinutes) {
        this.windowMinutes = windowMinutes;
    }
}
