package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.ResourceNotFoundException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ConversationStoreTests {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("conversation_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");
    ConversationStore store;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("14").load().migrate();
        Flyway.configure().dataSource(source).target("15").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at,display_name,email,password_hash,username) VALUES"
                + "(1,NOW(),NOW(),'Alice','alice@test.local','hash','alice'),(2,NOW(),NOW(),'Bob','bob@test.local','hash','bob')");
        store = new ConversationStore(jdbc, new DataSourceTransactionManager(source));
    }

    @Test
    void ownershipAndCascadeApplyToConversationMessagesAndEvents() {
        long alice = store.create(1).conversation().id();
        long bob = store.create(2).conversation().id();
        assertEquals(1, store.list(1).size());
        assertThrows(ResourceNotFoundException.class, () -> store.detail(1, bob, 0));
        assertThrows(ResourceNotFoundException.class, () -> store.delete(1, bob));
        jdbc.update("INSERT INTO agent_conversation_message VALUES(?,0,?)", alice, "{\"role\":\"user\",\"content\":\"你好\"}");
        event(alice, 1, "ask", "{\"question\":\"你好\"}");
        jdbc.update("DELETE FROM app_user WHERE id=1");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_event", Integer.class));
        assertEquals(bob, store.list(2).getFirst().id());
    }

    @Test
    void projectsEveryCardAndKeepsCursorIndependentOfFullTurns() {
        long id = store.create(1).conversation().id();
        event(id, 1, "ask", "{\"question\":\"分析\"}");
        event(id, 2, "note", "{\"content\":\"先查简历\"}");
        event(id, 3, "confirmation_required", "{\"toolCallId\":\"a\",\"tool\":\"start_analysis\",\"arguments\":{}}");
        event(id, 4, "done", "{\"state\":\"AWAITING_CONFIRMATION\"}");
        event(id, 5, "decide", "{\"approved\":true,\"toolCallId\":\"a\"}");
        event(id, 6, "step_started", "{\"toolCallId\":\"a\",\"tool\":\"start_analysis\",\"arguments\":\"{}\"}");
        event(id, 7, "step", "{\"toolCallId\":\"a\",\"tool\":\"start_analysis\",\"ok\":true,\"resultPreview\":\"{\\\"analysisId\\\":7}\"}");
        event(id, 8, "browser_task_required", "{\"taskId\":\"b\",\"expiresAt\":1000}");
        event(id, 9, "message", "{\"content\":\"完成\"}");
        event(id, 10, "done", "{\"state\":\"COMPLETED\"}");
        var detail = store.detail(1, id, 7);
        assertEquals(3, detail.events().size());
        var turn = detail.turns().get(0);
        assertEquals("先查简历", turn.path("notes").get(0).asText());
        assertEquals("approved", turn.path("decision").asText());
        assertEquals(7, turn.path("analysisId").asLong());
        assertEquals("b", turn.path("browserTask").path("taskId").asText());
        assertEquals("完成", turn.path("answer").asText());
        assertTrue(turn.path("unfinished").isEmpty());
        store.delete(1, id);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_event", Integer.class));
    }

    private void event(long id, int sequence, String type, String payload) {
        jdbc.update("INSERT INTO agent_conversation_event VALUES(?,?,?,?,?,?,NOW())", id, sequence, "turn", "run", type, payload);
    }
}
