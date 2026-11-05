package com.arthur.jdragresume.integration;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Compares the migrated MySQL schema to the JPA string columns and the manually maintained JDBC schema. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class SchemaColumnLengthsMySqlTests {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_schema_lengths_test")
            .withUsername("jd_test")
            .withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("app.jwt.secret", () -> "repository-test-secret-at-least-32-bytes");
    }

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void annotatedEntityStringLengthsMatchPhysicalColumns() {
        Map<String, Integer> physical = physicalColumnLengths();
        for (var entity : entityManager.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            Table table = type.getAnnotation(Table.class);
            String tableName = table == null || table.name().isBlank() ? snake(type.getSimpleName()) : table.name();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    Column column = field.getAnnotation(Column.class);
                    if (column == null || field.isAnnotationPresent(Lob.class)) continue;
                    boolean textualEnum = field.getType().isEnum()
                            && field.isAnnotationPresent(Enumerated.class)
                            && field.getAnnotation(Enumerated.class).value() == EnumType.STRING;
                    if (field.getType() != String.class && !textualEnum) continue;
                    if (!column.columnDefinition().isBlank() && !column.columnDefinition().toLowerCase().startsWith("varchar")) {
                        continue;
                    }
                    String name = column.name().isBlank() ? snake(field.getName()) : column.name();
                    String identity = tableName + "." + name;
                    assertNotNull(physical.get(identity), "Missing JPA mapped string column: " + identity);
                    assertEquals(column.length(), physical.get(identity), identity);
                }
            }
        }
    }

    @Test
    void manuallyMaintainedJdbcColumnsMatchPhysicalLengths() {
        Map<String, Integer> physical = physicalColumnLengths();
        // All VARCHAR columns of tables written with JdbcTemplate rather than JPA.
        Map<String, Integer> jdbcColumns = Map.ofEntries(
                Map.entry("agent_conversation.title", 120),
                Map.entry("agent_conversation.state", 40),
                Map.entry("agent_conversation.current_turn", 36),
                Map.entry("agent_conversation.active_run", 36),
                Map.entry("agent_conversation_event.turn_id", 36),
                Map.entry("agent_conversation_event.run_id", 36),
                Map.entry("agent_conversation_event.type", 40),
                Map.entry("boss_page_reservation.reservation_id", 240)
        );
        for (var entry : jdbcColumns.entrySet()) {
            assertEquals(entry.getValue(), physical.get(entry.getKey()), entry.getKey());
        }
    }

    private Map<String, Integer> physicalColumnLengths() {
        Map<String, Integer> columns = new LinkedHashMap<>();
        jdbc.query("""
                select table_name, column_name, character_maximum_length
                from information_schema.columns where table_schema = database()
                """, rs -> {
            Number length = (Number) rs.getObject(3);
            if (length != null) columns.put(rs.getString(1) + "." + rs.getString(2), length.intValue());
        });
        return columns;
    }

    private static String snake(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
