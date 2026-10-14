package io.github.sudoitir.rahdar.kernel.jobs.internal;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The locks that make an installation-wide job run on one instance per tick (ADR-0125): ShedLock
 * over the {@code shedlock} table, with the database's clock so instances' clocks need not agree.
 * The job runner extends a lock itself while its run lasts (ADR-0194), so a crashed holder frees
 * it within {@code lockAtMostFor}.
 */
@Configuration(proxyBeanMethods = false)
class JobLocks {

    @Bean
    LockProvider jobLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
