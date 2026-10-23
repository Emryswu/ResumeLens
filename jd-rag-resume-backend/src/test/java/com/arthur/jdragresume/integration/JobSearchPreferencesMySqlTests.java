package com.arthur.jdragresume.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JobSearchPreferencesMySqlTests {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("job_preferences_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @Test
    void preferencesAreOwnedAndCascadeOnAccountDelete() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_user (id, created_at, updated_at, display_name, email, password_hash, username)
                    VALUES (1,NOW(),NOW(),'甲','a@example.test','hash','alice'),
                           (2,NOW(),NOW(),'乙','b@example.test','hash','bob')
                    """);
            statement.executeUpdate("""
                    INSERT INTO job_search_preferences
                        (user_id, available_months, days_per_week, preferred_district, district_required, created_at, updated_at)
                    VALUES (1,6,5,'龙岗区',0,NOW(),NOW()),
                           (2,3,3,'南山区',1,NOW(),NOW())
                    """);
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO job_search_preferences
                        (user_id, available_months, days_per_week, created_at, updated_at)
                    VALUES (1,5,5,NOW(),NOW())
                    """));
            statement.executeUpdate("DELETE FROM app_user WHERE id=1");
            try (var rows = statement.executeQuery(
                    "SELECT user_id, available_months, preferred_district FROM job_search_preferences")) {
                assertTrue(rows.next());
                assertEquals(2L, rows.getLong(1));
                assertEquals(3, rows.getInt(2));
                assertEquals("南山区", rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }
}
