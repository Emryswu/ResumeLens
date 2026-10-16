package com.arthur.jdragresume.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class AnalysisReportItemsMySqlTests {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("report_items_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @Test
    void flywayUpgradesV11AndKeepsExistingReportsReadableAsNullItems() throws Exception {
        var url = MYSQL.getJdbcUrl();
        Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("10").load().migrate();
        try (var connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_user (id,created_at,updated_at,display_name,email,password_hash,username) VALUES "
                    + "(1,NOW(),NOW(),'Alice','alice@example.test','hash','alice')");
            statement.executeUpdate("INSERT INTO resume (id,created_at,updated_at,candidate_name,title,user_id,raw_text) VALUES "
                    + "(1,NOW(),NOW(),'Alice','Java 简历',1,'Java Spring Boot')");
            statement.executeUpdate("INSERT INTO job_description (id,created_at,updated_at,company_name,description,title,user_id) VALUES "
                    + "(1,NOW(),NOW(),'Example Co','Backend role','Java engineer',1)");
            statement.executeUpdate("INSERT INTO analysis_history (id,created_at,updated_at,match_score,status,summary,strengths,missing_skills,"
                    + "job_description_id,resume_id,user_id) VALUES "
                    + "(1,NOW(),NOW(),87,'COMPLETED','旧报告','熟悉 Java [chunk-0]','缺少 Kafka',1,1,1)");

            var upgraded = Flyway.configure().dataSource(url, MYSQL.getUsername(), MYSQL.getPassword()).target("11").load();
            assertEquals(1, upgraded.migrate().migrationsExecuted);

            try (var rows = statement.executeQuery("SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_history' "
                    + "AND COLUMN_NAME IN ('strength_items','gap_items') ORDER BY COLUMN_NAME")) {
                for (var column : new String[]{"gap_items", "strength_items"}) {
                    assertTrue(rows.next(), column);
                    assertEquals(column, rows.getString(1));
                    assertEquals("longtext", rows.getString(2));
                    assertEquals("YES", rows.getString(3));
                }
                assertFalse(rows.next());
            }
            // An old report keeps its text columns and has no structured items; the API then falls back to the text.
            try (var rows = statement.executeQuery("SELECT strengths, missing_skills, strength_items, gap_items FROM analysis_history WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals("熟悉 Java [chunk-0]", rows.getString(1));
                assertEquals("缺少 Kafka", rows.getString(2));
                assertNull(rows.getString(3));
                assertNull(rows.getString(4));
            }
            statement.executeUpdate("UPDATE analysis_history SET strength_items = "
                    + "'[{\"title\":\"Java\",\"detail\":\"\",\"chunks\":[0],\"category\":\"TECH_STACK\",\"source\":\"MODEL\"}]' WHERE id = 1");
            try (var rows = statement.executeQuery("SELECT strength_items FROM analysis_history WHERE id = 1")) {
                assertTrue(rows.next());
                assertTrue(rows.getString(1).contains("\"TECH_STACK\""));
            }
        }
    }
}
