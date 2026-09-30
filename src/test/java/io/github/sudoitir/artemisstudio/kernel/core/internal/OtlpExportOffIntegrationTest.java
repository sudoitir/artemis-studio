package io.github.sudoitir.artemisstudio.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.sudoitir.artemisstudio.support.MockOtlpServer;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * By default Studio sends nothing (telemetry-export spec): with an endpoint configured but the exporters left
 * off, no exporter, meter registry or log appender exists, and an endpoint that would accept everything
 * receives nothing.
 */
class OtlpExportOffIntegrationTest extends PostgresIntegrationTest {

    private static final MockOtlpServer OTLP = new MockOtlpServer();

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("management.otlp.metrics.export.url", () -> OTLP.endpoint() + "/v1/metrics");
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> OTLP.endpoint() + "/v1/traces");
        registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> OTLP.endpoint() + "/v1/logs");
    }

    @AfterAll
    static void stop() {
        OTLP.close();
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    ObservationRegistry observations;

    @Test
    void nothingIsCreatedAndNothingIsSent() throws Exception {
        Observation.createNotStarted("test.off", observations)
                .observe(() -> LoggerFactory.getLogger("off").error("an error line"));
        Thread.sleep(Duration.ofSeconds(3));

        assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(LogRecordExporter.class)).isEmpty();
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.iteratorForAppenders()).toIterable().noneMatch(OpenTelemetryAppender.class::isInstance);
        assertThat(OTLP.requests()).isZero();
    }
}
