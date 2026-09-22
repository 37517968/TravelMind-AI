package com.travelmind.aiagent.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationContainerTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("travelmind_ai")
            .withUsername("test")
            .withPassword("test");

    @Test
    void shouldMigrateEmptyDatabaseAndAlignLikeTargetType() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();

        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection connection = MYSQL.createConnection("");
             ResultSet columns = connection.getMetaData()
                     .getColumns(MYSQL.getDatabaseName(), null, "user_like", "targetType")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("TYPE_NAME")).isIn("TINYINT", "TINYINT UNSIGNED");
        }

        try (Connection connection = MYSQL.createConnection("");
             ResultSet tables = connection.getMetaData()
                     .getTables(MYSQL.getDatabaseName(), null, null, new String[]{"TABLE"})) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (tables.next()) names.add(tables.getString("TABLE_NAME"));
            assertThat(names).contains("agent_task", "agent_workflow_checkpoint", "outbox_event",
                    "knowledge_index_state", "knowledge_chunk", "tool_audit_log", "mcp_tool_schema");
        }

        try (Connection connection = MYSQL.createConnection("");
             ResultSet columns = connection.getMetaData()
                     .getColumns(MYSQL.getDatabaseName(), null, "travel_plan", "knowledge_version")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("TYPE_NAME")).isEqualTo("BIGINT");
        }
    }
}
