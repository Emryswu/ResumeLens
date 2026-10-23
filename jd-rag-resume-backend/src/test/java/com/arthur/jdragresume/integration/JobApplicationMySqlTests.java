package com.arthur.jdragresume.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JobApplicationMySqlTests {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("application_feedback_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @Test
    void upgradeKeepsFeedbackUniquePerUserAndCascadesOnlyDeletedJobs() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("15").load().migrate();
        assertEquals(1, Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("16").load().migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, created_at, updated_at, display_name, email, password_hash, username)
                    VALUES (1,NOW(),NOW(),'甲','a@example.test','hash','alice'),
                           (2,NOW(),NOW(),'乙','b@example.test','hash','bob')
                    """);
            statement.executeUpdate("""
                    INSERT INTO job_description (id, created_at, updated_at, company_name, description, title, user_id, version, capture_depth)
                    VALUES (11,NOW(),NOW(),'甲公司','正文','Java',1,0,'FULL'),
                           (22,NOW(),NOW(),'乙公司','正文','Java',2,0,'FULL')
                    """);
            statement.executeUpdate("""
                    INSERT INTO job_application (user_id, job_description_id, status, note, created_at, updated_at)
                    VALUES (1,11,'APPLIED','已投递',NOW(),NOW()),
                           (2,22,'NOT_INTERESTED','距离太远',NOW(),NOW())
                    """);
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO job_application (user_id, job_description_id, status, note, created_at, updated_at)
                    VALUES (1,11,'APPLIED','重复',NOW(),NOW())
                    """));
            statement.executeUpdate("DELETE FROM job_description WHERE id=11");
            try (var rows = statement.executeQuery("SELECT user_id, job_description_id, status, note FROM job_application")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1));
                assertEquals(22, rows.getLong(2));
                assertEquals("NOT_INTERESTED", rows.getString(3));
                assertEquals("距离太远", rows.getString(4));
                assertFalse(rows.next());
            }
        }
    }
}
