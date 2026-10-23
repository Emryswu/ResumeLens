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
            return action.apply(rows.getFirst());
        });
    }

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
