package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.collect.CollectionSession;
import com.arthur.jdragresume.collect.CollectorMode;
import java.util.function.Consumer;

/** Resolved once per turn on the request thread; services still re-check ownership themselves. */
public record AgentToolContext(AppUser user, com.arthur.jdragresume.agent.ConversationStore.Run run,
                               CollectorMode collectorMode, String toolCallId, long deadline,
                               Consumer<CollectionSession.Progress> progress) {
    public AgentToolContext(AppUser user) { this(user, null); }
    public AgentToolContext(AppUser user, com.arthur.jdragresume.agent.ConversationStore.Run run) {
        this(user, run, CollectorMode.AUTO);
    }
    public AgentToolContext(AppUser user, com.arthur.jdragresume.agent.ConversationStore.Run run, CollectorMode mode) {
        this(user, run, mode, null, Long.MAX_VALUE, event -> {});
    }
    public AgentToolContext forCollection(String callId, long deadline, Consumer<CollectionSession.Progress> sink) {
        return new AgentToolContext(user, run, collectorMode, callId, deadline, sink);
    }
    public CollectionSession collection() {
        return new CollectionSession(user.getId(), run == null ? null : Long.toString(run.conversationId()),
                run == null ? null : run.conversationId() + ":" + run.turnId() + ":" + toolCallId,
                toolCallId, collectorMode, deadline, progress);
    }
}
