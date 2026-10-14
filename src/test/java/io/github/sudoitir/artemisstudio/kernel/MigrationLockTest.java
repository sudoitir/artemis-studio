package io.github.sudoitir.artemisstudio.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.blagerweij.sessionlock.PGLockService;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.sql.Connection;
import javax.sql.DataSource;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.lockservice.LockServiceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Liquibase takes its migration lock as a session advisory lock, which a dead replica's connection releases. */
class MigrationLockTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void liquibaseUsesTheSessionAdvisoryLock() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Database database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            assertThat(LockServiceFactory.getInstance().getLockService(database))
                    .isInstanceOf(PGLockService.class);
        }
    }
}
