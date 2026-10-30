package com.arthur.jdragresume.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

@Component
@ConfigurationProperties(prefix = "app.agent")
@Validated
public class AgentProperties {
    /** Regular model calls per turn; one additional read-only call closes a turn at the limit. */
    private int maxSteps = 12;
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
    @Min(1)
    private int maxPlanningSteps = 12;
    @Min(0)
    private int maxReplans = 2;
    @Min(1)
    private int summaryThresholdTurns = 12;
    @Min(1)
    private int recentTurns = 4;
    @Min(1)
    private int summaryMaxChars = 2000;
    @Min(1)
    private int memoryRecallLimit = 5;
    /**
     * How long the server waits for the browser to report on a task it handed out. A full
     * collection is at most 3 pages with a 3-7 s pause and a 30 s load limit each, so this leaves
     * room; past it the report is refused and the model says the task expired.
     */
    @Min(10)
    private long browserTaskTimeoutSeconds = 180;
    /** Upper bound on the serialized browser report; 45 listings fit in well under this. */
    @Min(1)
    private int maxBrowserReportChars = 200_000;
    /**
     * How long a FULL JD read from a BOSS detail page stays verified. Older verifications count as unverified for
     * ranking, the verification budget and the recommendation gate alike; {@link JdFreshness} is the only reader.
     */
    @Min(1)
    private int verifiedJdMaxAgeDays = 7;

    public int getMaxPlanningSteps() { return maxPlanningSteps; }
    public void setMaxPlanningSteps(int value) { maxPlanningSteps = value; }
    public int getMaxReplans() { return maxReplans; }
    public void setMaxReplans(int value) { maxReplans = value; }
    public int getSummaryThresholdTurns() { return summaryThresholdTurns; }
    public void setSummaryThresholdTurns(int value) { summaryThresholdTurns = value; }
    public int getRecentTurns() { return recentTurns; }
    public void setRecentTurns(int value) { recentTurns = value; }
    public int getSummaryMaxChars() { return summaryMaxChars; }
    public void setSummaryMaxChars(int value) { summaryMaxChars = value; }
    public int getMemoryRecallLimit() { return memoryRecallLimit; }
    public void setMemoryRecallLimit(int value) { memoryRecallLimit = value; }
    public long getBrowserTaskTimeoutSeconds() { return browserTaskTimeoutSeconds; }
    public void setBrowserTaskTimeoutSeconds(long value) { browserTaskTimeoutSeconds = value; }
    public int getVerifiedJdMaxAgeDays() { return verifiedJdMaxAgeDays; }
    public void setVerifiedJdMaxAgeDays(int value) { verifiedJdMaxAgeDays = value; }
    public int getMaxBrowserReportChars() { return maxBrowserReportChars; }
    public void setMaxBrowserReportChars(int value) { maxBrowserReportChars = value; }

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
