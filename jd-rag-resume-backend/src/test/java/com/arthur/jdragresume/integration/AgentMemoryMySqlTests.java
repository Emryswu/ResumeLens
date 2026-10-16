package com.arthur.jdragresume.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class AgentMemoryMySqlTests {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("agent_memory_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @Test
    void flywayUpgradesV9AndDeletingAUserCascadesOnlyTheirMemories() throws Exception {
        var baseline = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("9").load();
        baseline.migrate();
        // Pin the upgrade to V10 so later migrations do not change what this test measures.
        var upgraded = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("10").load();
        assertEquals(1, upgraded.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user (id,created_at,updated_at,display_name,email,password_hash,username) VALUES "
                    + "(1,NOW(),NOW(),'Alice','alice@example.test','hash','alice'),(2,NOW(),NOW(),'Bob','bob@example.test','hash','bob')");
            statement.executeUpdate("INSERT INTO agent_memory (created_at,updated_at,user_id,category,content) VALUES "
                    + "(NOW(),NOW(),1,'target_city','上海'),(NOW(),NOW(),2,'target_city','北京')");
            statement.executeUpdate("DELETE FROM app_user WHERE id=1");
            try (var rows = statement.executeQuery("SELECT user_id, content FROM agent_memory")) {
                assertTrue(rows.next());
                assertEquals(2L, rows.getLong(1));
                assertEquals("北京", rows.getString(2));
                assertFalse(rows.next());
            }
        }
    }
}
