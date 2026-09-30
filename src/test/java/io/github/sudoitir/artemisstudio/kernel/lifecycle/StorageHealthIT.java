package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class StorageHealthIT extends PostgresIntegrationTest {

    @Autowired
    StorageHealthService health;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyStudioTableIsReportedAndPartitionsAreFoldedIntoTheirParent() {
        List<StorageHealthService.TableHealth> tables = health.health();

        assertThat(tables).extracting(StorageHealthService.TableHealth::name).contains("audit_event", "metric_sample");
        assertThat(tables).extracting(StorageHealthService.TableHealth::name).noneMatch(n -> n.matches(".*_\\d{8}"));
        assertThat(tables)
                .filteredOn(t -> t.name().equals("metric_sample"))
                .singleElement()
                .satisfies(t -> assertThat(t.partitioned()).isTrue());
    }

    @Test
    void aSampleRecordsEveryTableSize() {
        jdbc.update("DELETE FROM storage_sample");

        health.sample();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM storage_sample WHERE table_name = 'audit_event'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void aMissingUpcomingDailyPartitionIsReported() {
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        Set<String> children = Set.of("t_20260930", "t_20261001", "t_20261003", "t_default");

        assertThat(StorageHealthService.missingPartitions(children, "t", now))
                .containsExactly(LocalDate.parse("2026-10-02"));
        assertThat(StorageHealthService.missingPartitions(Set.of("t_default"), "t", now))
                .isEmpty();
    }
}
