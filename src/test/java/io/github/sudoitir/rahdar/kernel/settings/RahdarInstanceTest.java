package io.github.sudoitir.rahdar.kernel.settings;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Replicas booting together on an empty database settle on one instance id. */
class RahdarInstanceTest extends PostgresIntegrationTest {

    @Autowired
    RahdarSettingRepository settings;

    @Autowired
    RahdarInstance running;

    @Test
    void concurrentFirstBootMintsOnce() {
        settings.deleteById(RahdarInstance.SETTING_KEY);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> boot = () -> {
            RahdarInstance instance = new RahdarInstance(settings);
            start.await();
            return instance.id();
        };
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<String>> ids = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(boot))
                    .toList();
            start.countDown();
            assertThat(ids.stream().map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }))
                    .containsOnly(new RahdarInstance(settings).id());
        } finally {
            // Put back the id the running application holds in memory.
            settings.deleteById(RahdarInstance.SETTING_KEY);
            settings.insertIfAbsent(RahdarInstance.SETTING_KEY, '"' + running.id() + '"');
        }
    }
}
