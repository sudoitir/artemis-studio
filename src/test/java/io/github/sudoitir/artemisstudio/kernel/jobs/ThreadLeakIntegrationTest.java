package io.github.sudoitir.artemisstudio.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat;
import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.stream.SseHub;
import io.github.sudoitir.artemisstudio.kernel.stream.Subscriber;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * No thread outlives the context (operational-health spec, ADR-0194): Studio starts, serves an event stream and
 * runs a job, then stops, and every thread it named for itself, virtual ones included, is gone. So is every
 * non-daemon thread the test did not already have, which would keep a process alive after shutdown.
 *
 * <p>Threads that existed before this context started belong to the test framework or to other cached contexts,
 * and are not counted. A thread a cached context's own job started a moment ago ends within moments, so the check
 * waits for a clean sample; a leak never ends. It polls with {@code Thread.sleep}, since
 * Awaitility's own thread is non-daemon and would be counted.
 */
class ThreadLeakIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> STUDIO_PREFIXES =
            List.of("job-", "jobs-", "scrape-", "sse-", "studio-", "vault-", "plugin-", "events-");

    private static final String LEAK_JOB = "thread-leak-check";

    @Configuration(proxyBeanMethods = false)
    static class LeakJob {
        @Bean
        ScheduledJob threadLeakJob() {
            return ScheduledJob.fixedDelay(
                    LEAK_JOB, "test", ScheduledJob.Scope.INSTANCE, () -> Duration.ofMillis(50), () -> {});
        }
    }

    /** A thread by identity, so it can be told from the ones that were alive before. */
    private record Seen(long id, String name, boolean daemon) {}

    @Test
    void nothingStudioNamedAndNoNonDaemonThreadSurvivesTheContext() throws Exception {
        Set<Long> before = new HashSet<>();
        sample().forEach(thread -> before.add(thread.id()));

        List<String> properties = new ArrayList<>(connectionProperties());
        properties.add("server.port=0");
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        ArtemisStudioApplication.class, LeakJob.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
        try {
            waitFor(
                    Duration.ofSeconds(30),
                    () -> context.getBean(JobStatuses.class).all().stream()
                            .anyMatch(s -> LEAK_JOB.equals(s.id()) && s.runs() >= 1));
            StudioPrincipal administrator = new StudioPrincipal(
                    UUID.randomUUID(),
                    "admin",
                    Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("*"))),
                    false);
            context.getBean(SseHub.class)
                    .register(
                            UUID.randomUUID(),
                            new Subscriber(mock(SseEmitter.class), Set.of("queues"), null, null, administrator));
            // the check can see what it looks for: while the context is open these are alive
            assertThat(survivors(before)).anyMatch(name -> name.startsWith("jobs-timer"));
            assertThat(survivors(before)).anyMatch(name -> name.startsWith("sse-drain"));
        } finally {
            context.close();
        }

        List<String> survivors;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            survivors = survivors(before);
            Thread.sleep(100);
        } while (!survivors.isEmpty() && System.nanoTime() < deadline);
        assertThat(survivors).as("threads that outlived the context").isEmpty();
    }

    private static void waitFor(Duration within, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime())
                    .as("the condition holds within " + within)
                    .isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static List<String> survivors(Set<Long> before) throws IOException {
        List<String> survivors = new ArrayList<>();
        for (Seen thread : sample()) {
            if (before.contains(thread.id())) {
                continue;
            }
            if (STUDIO_PREFIXES.stream().anyMatch(thread.name()::startsWith)) {
                survivors.add(thread.name() + " (id " + thread.id() + ")");
            } else if (!thread.daemon()) {
                survivors.add("non-daemon " + thread.name() + " (id " + thread.id() + ")");
            }
        }
        return survivors;
    }

    /**
     * Every live thread: the platform threads with their daemon flag, and every thread of the JVM's own thread
     * dump, which alone lists virtual ones. Virtual threads are always daemon.
     */
    private static List<Seen> sample() throws IOException {
        List<Seen> all = new ArrayList<>();
        Set<Long> platform = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            platform.add(thread.threadId());
            all.add(new Seen(thread.threadId(), thread.getName(), thread.isDaemon()));
        }
        Path dir = Files.createTempDirectory("thread-leak");
        Path file = dir.resolve("threads.json");
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(file.toString(), ThreadDumpFormat.JSON);
            for (JsonNode container : JsonMapper.builder()
                    .build()
                    .readTree(file.toFile())
                    .path("threadDump")
                    .path("threadContainers")) {
                for (JsonNode thread : container.path("threads")) {
                    long id = thread.path("tid").asLong();
                    if (!platform.contains(id)) {
                        all.add(new Seen(id, thread.path("name").asString(""), true));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
        return all;
    }
}
