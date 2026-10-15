package com.example.grid;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests backed by a real PostgreSQL 16 container.
 */
@Testcontainers
public abstract class PostgreSqlTestContainerBase {

    @Container
    @ServiceConnection
    protected static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("grid_settlement_test")
                    .withUsername("test_user")
                    .withPassword("test_secret");
}
