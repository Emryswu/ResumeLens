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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    void checkpointUsesBatchUpdateWithoutFullDeleteOnInitialMessages() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header("run-1", System.currentTimeMillis() + 60_000);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));
        when(jdbc.query(eq("SELECT position, payload FROM agent_conversation_message WHERE conversation_id=? ORDER BY position"),
                anyRowMapper(), eq(10L))).thenReturn(List.of());

        List<AgentMessage> messages = List.of(
                AgentMessage.user("你好"),
                AgentMessage.assistant("收到", null),
                AgentMessage.user("继续")
        );
        var run = new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L);

        store.checkpoint(run, messages, "sum");

        verify(jdbc, never()).update(contains("DELETE FROM agent_conversation_message"), anyLong());
        verify(jdbc, never()).update(contains("DELETE FROM agent_conversation_message"), anyLong(), anyInt());
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
    void checkpointAppendsOnlyNewTailWithoutDeletingUnchangedPrefix() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header("run-1", System.currentTimeMillis() + 60_000);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));

        AgentMessage first = AgentMessage.user("你好");
        AgentMessage second = AgentMessage.assistant("收到", null);
        AgentMessage third = AgentMessage.tool("c1", "{\"ok\":true}");
        stubStoredMessages(jdbc, 10L, List.of(first, second));

        var run = new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L);
        store.checkpoint(run, List.of(first, second, third), "sum");

        verify(jdbc, never()).update(contains("DELETE FROM agent_conversation_message"), anyLong());
        verify(jdbc, never()).update(contains("DELETE FROM agent_conversation_message"), anyLong(), anyInt());

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc, times(1)).batchUpdate(eq("INSERT INTO agent_conversation_message VALUES(?,?,?)"), setterCaptor.capture());
        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertEquals(1, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);
        verify(ps).setLong(1, 10L);
        verify(ps).setInt(2, 2);
        verify(ps).setString(3, ConversationStore.json(third));
    }

    @Test
    void checkpointWithIdenticalMessagesSkipsBothDeleteAndInsert() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header("run-1", System.currentTimeMillis() + 60_000);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));

        AgentMessage first = AgentMessage.user("你好");
        AgentMessage second = AgentMessage.assistant("收到", null);
        stubStoredMessages(jdbc, 10L, List.of(first, second));

        var run = new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L);
        store.checkpoint(run, List.of(first, second), "sum");

        verify(jdbc, never()).update(contains("DELETE FROM agent_conversation_message"), anyLong());
        verify(jdbc, never()).update(contains("DELETE FROM agent_conversation_message"), anyLong(), anyInt());
        verify(jdbc, never()).batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class));
    }

    @Test
    void checkpointRewritesOnlyTailFromFirstDivergenceWhenNormalizedOrTruncated() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header("run-1", System.currentTimeMillis() + 60_000);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));

        AgentMessage m0 = AgentMessage.user("第一轮");
        AgentMessage m1 = AgentMessage.assistant("旧回复", null);
        AgentMessage m2 = AgentMessage.user("第二轮");
        stubStoredMessages(jdbc, 10L, List.of(m0, m1, m2));

        AgentMessage m1Updated = AgentMessage.assistant("规范化后回复", null);
        var run = new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L);
        store.checkpoint(run, List.of(m0, m1Updated), "sum");

        verify(jdbc, times(1)).update("DELETE FROM agent_conversation_message WHERE conversation_id=? AND position>=?", 10L, 1);

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbc, times(1)).batchUpdate(eq("INSERT INTO agent_conversation_message VALUES(?,?,?)"), setterCaptor.capture());
        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertEquals(1, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);
        verify(ps).setLong(1, 10L);
        verify(ps).setInt(2, 1);
        verify(ps).setString(3, ConversationStore.json(m1Updated));
    }

    @Test
    void deltaSyncMatchesFullReplacementAcrossAppendRewriteAndTruncation() throws Exception {
        Map<Integer, String> table = new TreeMap<>();
        AtomicInteger deleteCount = new AtomicInteger();
        AtomicInteger insertedRows = new AtomicInteger();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        ConversationStore.Header activeHeader = header("run-1", System.currentTimeMillis() + 60_000);
        when(jdbc.query(contains("FROM agent_conversation WHERE id=? AND user_id=? FOR UPDATE"),
                anyRowMapper(), eq(10L), eq(1L))).thenReturn(List.of(activeHeader));
        when(jdbc.query(eq("SELECT position, payload FROM agent_conversation_message WHERE conversation_id=? ORDER BY position"),
                anyRowMapper(), eq(10L))).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            List<Object> rows = new ArrayList<>();
            int rowNum = 0;
            for (Map.Entry<Integer, String> entry : table.entrySet()) {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getInt(1)).thenReturn(entry.getKey());
                when(rs.getString(2)).thenReturn(entry.getValue());
                rows.add(mapper.mapRow(rs, rowNum++));
            }
            return rows;
        });
        when(jdbc.query(eq("SELECT payload FROM agent_conversation_message WHERE conversation_id=? ORDER BY position"),
                anyRowMapper(), eq(10L))).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            List<Object> rows = new ArrayList<>();
            int rowNum = 0;
            for (String payload : table.values()) {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString(1)).thenReturn(payload);
                rows.add(mapper.mapRow(rs, rowNum++));
            }
            return rows;
        });
        when(jdbc.update(eq("DELETE FROM agent_conversation_message WHERE conversation_id=? AND position>=?"),
                eq(10L), anyInt())).thenAnswer(inv -> {
            deleteCount.incrementAndGet();
            int fromPos = inv.getArgument(2);
            table.keySet().removeIf(pos -> pos >= fromPos);
            return 1;
        });
        when(jdbc.batchUpdate(eq("INSERT INTO agent_conversation_message VALUES(?,?,?)"),
                any(BatchPreparedStatementSetter.class))).thenAnswer(inv -> {
            BatchPreparedStatementSetter setter = inv.getArgument(1);
            int size = setter.getBatchSize();
            int[] counts = new int[size];
            for (int i = 0; i < size; i++) {
                int[] posHolder = new int[1];
                String[] payloadHolder = new String[1];
                PreparedStatement ps = mock(PreparedStatement.class);
                org.mockito.Mockito.doAnswer(a -> { posHolder[0] = a.getArgument(1); return null; }).when(ps).setInt(eq(2), anyInt());
                org.mockito.Mockito.doAnswer(a -> { payloadHolder[0] = a.getArgument(1); return null; }).when(ps).setString(eq(3), any());
                setter.setValues(ps, i);
                table.put(posHolder[0], payloadHolder[0]);
                insertedRows.incrementAndGet();
                counts[i] = 1;
            }
            return counts;
        });

        var run = new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L);
        List<AgentMessage> step1 = List.of(AgentMessage.user("Q1"), AgentMessage.assistant("A1", null));
        store.checkpoint(run, step1, "s1");
        assertEquals(0, deleteCount.get());
        assertEquals(2, insertedRows.get());
        assertEquals(step1, store.messages(10L));

        List<AgentMessage> step2 = List.of(
                step1.get(0), step1.get(1),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c1", "get_resume", "{}"))),
                AgentMessage.tool("c1", "{\"ok\":true}")
        );
        store.checkpoint(run, step2, "s2");
        assertEquals(0, deleteCount.get(), "append-only checkpoint must not execute DELETE");
        assertEquals(4, insertedRows.get(), "only 2 new rows should be inserted on step2");
        assertEquals(step2, store.messages(10L));

        List<AgentMessage> normalizedRewrite = List.of(
                step1.get(0),
                AgentMessage.assistant("Normalized A1", null)
        );
        store.checkpoint(run, normalizedRewrite, "s3");
        assertEquals(1, deleteCount.get());
        assertEquals(5, insertedRows.get(), "only diverged position 1 onward should be reinserted");
        assertEquals(normalizedRewrite, store.messages(10L));
    }

    private static void stubStoredMessages(JdbcTemplate jdbc, long conversationId, List<AgentMessage> stored) {
        when(jdbc.query(eq("SELECT position, payload FROM agent_conversation_message WHERE conversation_id=? ORDER BY position"),
                anyRowMapper(), eq(conversationId))).thenAnswer(inv -> {
            RowMapper<Object> mapper = inv.getArgument(1);
            List<Object> rows = new ArrayList<>();
            for (int i = 0; i < stored.size(); i++) {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                try {
                    when(rs.getInt(1)).thenReturn(i);
                    when(rs.getString(2)).thenReturn(ConversationStore.json(stored.get(i)));
                    rows.add(mapper.mapRow(rs, i));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            return rows;
        });
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

    @Test
    void isCurrentReturnsFalseWhenConversationRowIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ConversationStore store = new ConversationStore(jdbc, NOOP_TX);
        when(jdbc.query(
                eq("SELECT active_run=? AND run_expires_at>? FROM agent_conversation WHERE id=? AND user_id=?"),
                anyRowMapper(), eq("run-1"), anyLong(), eq(10L), eq(1L)))
                .thenReturn(List.of());

        assertFalse(store.isCurrent(new ConversationStore.Run(1L, 10L, "turn-1", "run-1", null, null, 0L)));
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
