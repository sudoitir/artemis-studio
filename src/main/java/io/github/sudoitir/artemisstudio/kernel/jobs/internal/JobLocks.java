package io.github.sudoitir.artemisstudio.kernel.jobs.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The locks that make an installation-wide job run on one instance per tick (ADR-0125): ShedLock
 * over the {@code shedlock} table, with the database's clock so instances' clocks need not agree,
 * and a keep-alive that extends a lock while its run lasts, so a long run never loses its lock and
 * a crashed holder frees it within {@code lockAtMostFor}.
 */
@Configuration(proxyBeanMethods = false)
class JobLocks {

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService jobLockKeepAlive() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("job-lock-keep-alive").daemon().factory());
    }

    @Bean
    LockingTaskExecutor jobLockExecutor(DataSource dataSource, ScheduledExecutorService jobLockKeepAlive) {
        var provider = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
        return new DefaultLockingTaskExecutor(new KeepAliveLockProvider(provider, jobLockKeepAlive));
    }
}
