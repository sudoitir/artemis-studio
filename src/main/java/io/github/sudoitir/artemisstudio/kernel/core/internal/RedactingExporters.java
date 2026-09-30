package io.github.sudoitir.artemisstudio.kernel.core.internal;

import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps every span and log exporter, Boot's OTLP ones and any other, so that what leaves Studio is redacted
 * (ADR-0133, ADR-0147). The exporter is the last point every record passes: the SDK's processors cannot change a
 * log body, and a span's exception event is recorded before an observation filter runs.
 */
@Configuration(proxyBeanMethods = false)
class RedactingExporters {

    @Bean
    static BeanPostProcessor exporterRedaction() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof LogRecordExporter exporter && !(bean instanceof RedactingLogRecordExporter)) {
                    return new RedactingLogRecordExporter(exporter);
                }
                if (bean instanceof SpanExporter exporter && !(bean instanceof RedactingSpanExporter)) {
                    return new RedactingSpanExporter(exporter);
                }
                return bean;
            }
        };
    }
}
