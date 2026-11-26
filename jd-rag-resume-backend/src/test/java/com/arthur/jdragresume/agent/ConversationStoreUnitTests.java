package com.arthur.jdragresume.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationStoreUnitTests {
    private static final PlatformTransactionManager NOOP_TX = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { }

        @Override
        public void rollback(TransactionStatus status) { }
    };

    @Test
    void checkpointUsesBatchUpdateInsteadOfRowByRowInserts() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header("run-1", System.currentTimeMillis() + 60_000);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));

        List<AgentMessage> messages = List.of(
                AgentMessage.user("你好"),
                AgentMessage.assistant("收到", null),
                AgentMessage.user("继续")
        );
        var run = new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L);

        store.checkpoint(run, messages, "sum");

        verify(jdbc).update("DELETE FROM agent_conversation_message WHERE conversation_id=?", 10L);
        verify(jdbc, never()).update(contains("INSERT INTO agent_conversation_message"), any(), any(), any());

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc, times(1)).batchUpdate(eq("INSERT INTO agent_conversation_message VALUES(?,?,?)"), setterCaptor.capture());
        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertEquals(3, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 1);
        verify(ps).setLong(1, 10L);
        verify(ps).setInt(2, 1);
        verify(ps).setString(eq(3), contains("收到"));
    }

    @Test
    void eventsAfterAndLatestEventPushFilteringDownToSqlWhileHeaderAvoidsEventQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header(null, null);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));

        store.header(1L, 10L);
        verify(jdbc, never()).query(contains("agent_conversation_event"), anyRowMapper(), any());
        verify(jdbc, never()).query(contains("agent_conversation_event"), anyRowMapper(), any(), any());

        var event = new ConversationStore.Event(8L, "turn", "run", "message",
                ConversationStore.read("{\"content\":\"hi\"}"), Instant.now().toString());
        when(jdbc.query(eq("SELECT * FROM agent_conversation_event WHERE conversation_id=? AND sequence>? ORDER BY sequence"),
                anyRowMapper(), eq(10L), eq(7L))).thenReturn(List.of(event));
        when(jdbc.query(eq("SELECT * FROM agent_conversation_event WHERE conversation_id=? ORDER BY sequence DESC LIMIT 1"),
                anyRowMapper(), eq(10L))).thenReturn(List.of(event));

        List<ConversationStore.Event> incremental = store.eventsAfter(1L, 10L, 7L);
        assertEquals(1, incremental.size());
        assertEquals(8L, incremental.getFirst().sequence());

        var latest = store.latestEvent(1L, 10L);
        assertTrue(latest.isPresent());
        assertEquals(8L, latest.get().sequence());
    }

    private static ConversationStore.Header header(String activeRun, Long expiresAt) {
        String now = Timestamp.from(Instant.now()).toInstant().toString();
        return new ConversationStore.Header(10L, "新对话", "IDLE", "turn-1", activeRun, expiresAt, 1L, now, now, false);
    }

    @SuppressWarnings("unchecked")
    private static <T> RowMapper<T> anyRowMapper() {
        return (RowMapper<T>) any(RowMapper.class);
    }
}
