package io.github.sudoitir.artemisstudio.platform.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Detection runs on the capture drain path, so a finding is aggregated in memory and written in one batch. */
class FindingsRecorderTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FindingsRecorder recorder = new FindingsRecorder(jdbc);

    @Test
    void repeatedDetectionsWriteNothingUntilFlushThenOneRowWithTheCount() {
        for (int i = 0; i < 1_000; i++) {
            recorder.record("orders", Location.PROPERTY, "contact", DataClass.EMAIL);
        }
        verifyNoInteractions(jdbc);

        recorder.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(1)).batchUpdate(anyString(), batch.capture());
        assertThat(batch.getValue()).singleElement().satisfies(row -> {
            assertThat(row[2]).isEqualTo(1_000L);
            assertThat(row[3]).isEqualTo("orders");
            assertThat(row[5]).isEqualTo("contact");
            assertThat(row[6]).isEqualTo("EMAIL");
        });
        assertThat(recorder.pendingCount()).isZero();
    }

    @Test
    void anEmptyFlushWritesNothing() {
        recorder.flush();

        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    void newFieldsBeyondTheCapAreDroppedAndCountedWhileKnownFieldsStillCount() {
        for (int i = 0; i < FindingsRecorder.MAX_PENDING; i++) {
            recorder.record("orders", Location.PROPERTY, "field-" + i, DataClass.EMAIL);
        }

        recorder.record("orders", Location.PROPERTY, "one-too-many", DataClass.EMAIL);
        recorder.record("orders", Location.PROPERTY, "field-0", DataClass.EMAIL);

        assertThat(recorder.dropped()).isEqualTo(1);
        assertThat(recorder.pendingCount()).isEqualTo(FindingsRecorder.MAX_PENDING);
    }

    @Test
    void aDetectionWithoutAnAddressIsNotAFinding() {
        recorder.record(null, Location.BODY, "", DataClass.PAN);

        assertThat(recorder.pendingCount()).isZero();
    }
}
