package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** Short row transactions serialize conversation changes; no transaction spans a model or network call. */
@Service
public class ConversationStore {
    static final ObjectMapper JSON = new ObjectMapper();
    final JdbcTemplate jdbc;
    final TransactionTemplate transactions;

    public ConversationStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<Header> list(long userId) {
        recoverExpired();
        return jdbc.query("SELECT * FROM agent_conversation WHERE user_id=? ORDER BY updated_at DESC,id DESC",
                (rs, n) -> header(rs), userId);
    }

    public Detail create(long userId) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO agent_conversation(user_id,title,created_at,updated_at) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, "新对话");
            var now = Timestamp.from(Instant.now());
            statement.setTimestamp(3, now);
            statement.setTimestamp(4, now);
            return statement;
        }, key);
        return detail(userId, key.getKey().longValue(), 0);
    }

    public Detail detail(long userId, long id, long after) {
        return locked(userId, id, row -> {
            var events = events(id);
            return new Detail(row, ConversationTurns.project(events), events.stream().filter(event -> event.sequence() > after).toList());
        });
    }

    public void delete(long userId, long id) {
        locked(userId, id, row -> {
            if (row.activeRun() != null) throw new BusinessException("AGENT_CONVERSATION_BUSY", "conversation is running");
            jdbc.update("DELETE FROM agent_conversation WHERE id=? AND user_id=?", id, userId);
            return null;
        });
    }

    <T> T locked(long userId, long id, Function<Header, T> action) {
        return transactions.execute(status -> {
            List<Header> rows = jdbc.query("SELECT * FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE",
                    (rs, n) -> header(rs), id, userId);
            if (rows.isEmpty()) throw new ResourceNotFoundException("conversation", id);
            Header row = rows.getFirst();
            if (row.activeRun() != null && row.runExpiresAt() <= System.currentTimeMillis()) {
                interrupt(row, "AGENT_INTERRUPTED", "服务执行已中断，可以继续提问");
                row = jdbc.queryForObject("SELECT * FROM agent_conversation WHERE id=?", (rs, n) -> header(rs), id);
            }
            return action.apply(row);
        });
    }

    /** Startup and normal reads only reclaim expired leases, including on a shared database. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverExpired() {
        var expired = jdbc.query("SELECT user_id,id FROM agent_conversation WHERE active_run IS NOT NULL AND run_expires_at<=?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, System.currentTimeMillis());
        for (long[] row : expired) {
            try { locked(row[0], row[1], header -> null); }
            catch (ResourceNotFoundException ignored) { /* Concurrent deletion after recovery is valid. */ }
        }
    }

    public Run accept(long userId, long id, String question, TranscriptPolicy.Approval approval,
                      TranscriptPolicy.BrowserReport browser, long timeoutMs, Function<List<AgentMessage>, TranscriptPolicy.Normalized> normalize) {
        return locked(userId, id, row -> {
            if (row.activeRun() != null) throw new BusinessException("AGENT_CONVERSATION_BUSY", "conversation is running");
            if (question != null && (row.state().equals("AWAITING_CONFIRMATION") || row.state().equals("AWAITING_BROWSER"))) {
                throw new BusinessException("AGENT_APPROVAL_MISMATCH", "answer the pending action first");
            }
            if (approval != null && !row.state().equals("AWAITING_CONFIRMATION")
                    || browser != null && !row.state().equals("AWAITING_BROWSER")) {
                throw new BusinessException("AGENT_APPROVAL_MISMATCH", "no matching pending action");
            }
            List<AgentMessage> messages = new ArrayList<>(messages(id));
            if (question != null) messages.add(AgentMessage.user(question));
            var normalized = normalize.apply(messages);
            String runId = UUID.randomUUID().toString();
            String turnId = question != null ? UUID.randomUUID().toString() : row.currentTurn();
            String summary = jdbc.queryForObject("SELECT summary FROM agent_conversation WHERE id=?", String.class, id);
            long acceptedAt = System.currentTimeMillis();
            var run = new Run(userId, id, turnId, runId, normalized, summary, acceptedAt);
            jdbc.update("UPDATE agent_conversation SET state='RUNNING',current_turn=?,active_run=?,run_expires_at=?,title=?,updated_at=? WHERE id=?",
                    turnId, runId, acceptedAt + timeoutMs + 5000, question != null && row.eventSequence() == 0
                            ? question.substring(0, Math.min(120, question.length())) : row.title(), Timestamp.from(Instant.now()), id);
            replaceMessages(id, messages);
            if (question != null) append(run, "ask", Map.of("question", question));
            else if (approval != null) append(run, "decide", Map.of("toolCallId", approval.toolCallId(), "approved", approval.approved()));
            else append(run, "browser_report", Map.of("outcome", browser.outcome()));
            return run;
        });
    }

    public List<AgentMessage> messages(long id) {
        return jdbc.query("SELECT payload FROM agent_conversation_message WHERE conversation_id=? ORDER BY position", (rs, n) -> {
            try { return JSON.readValue(rs.getString(1), AgentMessage.class); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("invalid stored model message", ex); }
        }, id);
    }

    private void replaceMessages(long id, List<AgentMessage> messages) {
        jdbc.update("DELETE FROM agent_conversation_message WHERE conversation_id=?", id);
        for (int i = 0; i < messages.size(); i++) {
            jdbc.update("INSERT INTO agent_conversation_message VALUES(?,?,?)", id, i, json(messages.get(i)));
        }
    }

    public void checkpoint(Run run, List<AgentMessage> messages, String summary) {
        owned(run, row -> {
            replaceMessages(run.conversationId(), messages);
            jdbc.update("UPDATE agent_conversation SET summary=? WHERE id=?", summary, run.conversationId());
            return null;
        });
    }

    public Event emit(Run run, String type, Object payload) {
        return owned(run, row -> {
            Event event = append(run, type, payload);
            if (type.equals("done")) {
                jdbc.update("UPDATE agent_conversation SET state=?,active_run=NULL,run_expires_at=NULL WHERE id=?",
                        event.data().path("state").asText(), run.conversationId());
            }
            return event;
        });
    }

    Event append(Run run, String type, Object payload) {
        jdbc.update("UPDATE agent_conversation SET event_sequence=event_sequence+1,updated_at=? WHERE id=?", Timestamp.from(Instant.now()), run.conversationId());
        long sequence = jdbc.queryForObject("SELECT event_sequence FROM agent_conversation WHERE id=?", Long.class, run.conversationId());
        String createdAt = Instant.now().toString();
        jdbc.update("INSERT INTO agent_conversation_event VALUES(?,?,?,?,?,?,?)", run.conversationId(), sequence, run.turnId(), run.runId(),
                type, json(payload), Timestamp.from(Instant.parse(createdAt)));
        return new Event(sequence, run.turnId(), run.runId(), type, JSON.valueToTree(payload), createdAt);
    }

    <T> T owned(Run run, Function<Header, T> action) {
        return locked(run.userId(), run.conversationId(), row -> {
            if (!run.runId().equals(row.activeRun())) throw new AgentEventSink.ClientGoneException(null);
            return action.apply(row);
        });
    }

    public boolean isCurrent(Run run) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active_run=? AND run_expires_at>? FROM agent_conversation WHERE id=? AND user_id=?",
                Boolean.class, run.runId(), System.currentTimeMillis(), run.conversationId(), run.userId()));
    }

    public void fail(Run run, String code, String message) {
        locked(run.userId(), run.conversationId(), row -> {
            if (run.runId().equals(row.activeRun())) interrupt(row, code, message);
            return null;
        });
    }

    private void interrupt(Header row, String code, String message) {
        long userId = jdbc.queryForObject("SELECT user_id FROM agent_conversation WHERE id=?", Long.class, row.id());
        var run = new Run(userId, row.id(), row.currentTurn(), row.activeRun(), null, null, 0);
        List<AgentMessage> messages = new ArrayList<>(messages(row.id()));
        Map<String, AgentMessage.ToolCall> open = new java.util.LinkedHashMap<>();
        for (var entry : messages) {
            if (entry.hasToolCalls()) entry.toolCalls().forEach(call -> open.put(call.id(), call));
            if (AgentMessage.TOOL.equals(entry.role())) open.remove(entry.toolCallId());
        }
        open.values().forEach(call -> messages.add(AgentMessage.tool(call.id(), ToolEnvelope.error(call.name(), code, message))));
        replaceMessages(row.id(), messages);
        append(run, "error", Map.of("code", code, "message", message));
        append(run, "done", Map.of("state", "FAILED", "steps", 0, "transcript", messages));
        jdbc.update("UPDATE agent_conversation SET state='FAILED',active_run=NULL,run_expires_at=NULL,browser_task=NULL,browser_arguments=NULL WHERE id=?", row.id());
    }

    public BrowserTaskRegistry.Task issueBrowser(Run run, String callId, String tool, String arguments, long now, long timeout) {
        return owned(run, row -> {
            var task = new BrowserTaskRegistry.Task("bt_" + UUID.randomUUID(), callId, tool, arguments, now, now + timeout);
            jdbc.update("UPDATE agent_conversation SET browser_task=?,browser_arguments=? WHERE id=?", json(task), arguments, row.id());
            return task;
        });
    }

    public BrowserTaskRegistry.Claim claimBrowser(Run run, String taskId, String callId, String arguments, long now) {
        return owned(run, row -> {
            String payload = jdbc.queryForObject("SELECT browser_task FROM agent_conversation WHERE id=?", String.class, row.id());
            if (payload == null) return new BrowserTaskRegistry.Claim(BrowserTaskRegistry.ClaimState.UNKNOWN, null);
            BrowserTaskRegistry.Task task;
            try { task = JSON.readValue(payload, BrowserTaskRegistry.Task.class); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("invalid stored browser task", ex); }
            if (!task.taskId().equals(taskId) || !task.matches(callId, arguments)) {
                return new BrowserTaskRegistry.Claim(BrowserTaskRegistry.ClaimState.UNKNOWN, null);
            }
            jdbc.update("UPDATE agent_conversation SET browser_task=NULL,browser_arguments=NULL WHERE id=?", row.id());
            return new BrowserTaskRegistry.Claim(now > task.expiresAt() ? BrowserTaskRegistry.ClaimState.EXPIRED : BrowserTaskRegistry.ClaimState.ACTIVE, task);
        });
    }

    public record Run(long userId, long conversationId, String turnId, String runId, TranscriptPolicy.Normalized input, String summary, long acceptedAt) {}

    List<Event> events(long id) {
        return jdbc.query("SELECT * FROM agent_conversation_event WHERE conversation_id=? ORDER BY sequence", (rs, n) ->
                new Event(rs.getLong("sequence"), rs.getString("turn_id"), rs.getString("run_id"), rs.getString("type"),
                        read(rs.getString("payload")), rs.getTimestamp("created_at").toInstant().toString()), id);
    }

    private Header header(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Header(rs.getLong("id"), rs.getString("title"), rs.getString("state"), rs.getString("current_turn"),
                rs.getString("active_run"), rs.getObject("run_expires_at", Long.class), rs.getLong("event_sequence"),
                rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString());
    }

    static JsonNode read(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("invalid stored conversation JSON", ex); }
    }

    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("cannot serialize conversation", ex); }
    }

    public record Header(long id, String title, String state, String currentTurn, String activeRun, Long runExpiresAt,
                         long eventSequence, String createdAt, String updatedAt) {}
    public record Event(long sequence, String turnId, String runId, String type, JsonNode data, String createdAt) {}
    public record Detail(Header conversation, JsonNode turns, List<Event> events) {}
}
