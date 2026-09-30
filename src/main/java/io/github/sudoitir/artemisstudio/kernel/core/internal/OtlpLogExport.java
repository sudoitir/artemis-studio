package io.github.sudoitir.artemisstudio.kernel.core.internal;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ships Studio's logs over OTLP, only when {@code management.logging.export.otlp.enabled} is on ({@code
 * OTEL_LOGS_EXPORTER=otlp}). Boot builds the SDK's log exporter but leaves the Logback appender to the application, so
 * this attaches one to the root logger and hands it the {@link OpenTelemetry} instance. The records are redacted
 * on their way out by {@link RedactingExporters} (ADR-0147).
 */
@Configuration(proxyBeanMethods = false)
class OtlpLogExport {

    @Bean
    @ConditionalOnBooleanProperty("management.logging.export.otlp.enabled")
    Appender otlpLogAppender(OpenTelemetry openTelemetry) {
        return new Appender(openTelemetry);
    }

    /** The appender, attached for as long as the context lives. */
    static final class Appender implements InitializingBean, DisposableBean {

        private final OpenTelemetry openTelemetry;
        private final OpenTelemetryAppender otlpAppender = new OpenTelemetryAppender();

        Appender(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterPropertiesSet() {
            LoggerContext logs = (LoggerContext) LoggerFactory.getILoggerFactory();
            otlpAppender.setContext(logs);
            otlpAppender.setName("OTLP");
            otlpAppender.start();
            logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(otlpAppender);
            OpenTelemetryAppender.install(openTelemetry);
        }

        @Override
        public void destroy() {
            ((LoggerContext) LoggerFactory.getILoggerFactory())
                    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                    .detachAppender(otlpAppender);
            otlpAppender.stop();
        }
    }
}
