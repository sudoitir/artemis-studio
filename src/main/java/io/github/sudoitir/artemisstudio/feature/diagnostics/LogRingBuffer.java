package io.github.sudoitir.artemisstudio.feature.diagnostics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import java.util.ArrayDeque;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * The most recent log lines, kept in memory for the support bundle (ADR-0146). Each line is formatted and passed
 * through {@link SecretRedactor} when it is appended, so what is stored is already redacted whether or not the
 * layout's own redacting converters (ADR-0133) were registered first. Studio logs to stdout only; this is the one
 * copy it can read back.
 */
public final class LogRingBuffer extends UnsynchronizedAppenderBase<ILoggingEvent> {

    static final String NAME = "studio-diagnostics";
    static final int CAPACITY = 5_000;
    static final int MAX_LINE = 8_192;
    private static final String PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p [%t] %logger{40} : %m%n%ex";

    private final ArrayDeque<String> lines = new ArrayDeque<>(CAPACITY);
    private final PatternLayout layout = new PatternLayout();

    private LogRingBuffer(LoggerContext context) {
        setName(NAME);
        setContext(context);
        layout.setContext(context);
        layout.setPattern(PATTERN);
        layout.start();
        start();
    }

    /**
     * The buffer on the root logger, attached first if it is not there. Boot's logging initialisation resets
     * the Logback context and drops it, so this is called again once each application context is initialised.
     */
    public static LogRingBuffer attached() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(NAME) instanceof LogRingBuffer buffer) {
            return buffer;
        }
        LogRingBuffer buffer = new LogRingBuffer(context);
        root.addAppender(buffer);
        return buffer;
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = SecretRedactor.redact(layout.doLayout(event));
        if (line.length() > MAX_LINE) {
            line = line.substring(0, MAX_LINE) + " …[truncated]\n";
        }
        synchronized (lines) {
            if (lines.size() == CAPACITY) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }
    }

    /** A copy, oldest first. */
    public List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }
}
