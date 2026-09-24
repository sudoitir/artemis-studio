package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers one email per delivery over SMTP (ADR-0105 D5). The channel's secret is the SMTP
 * password.
 *
 * <p>A {@link JavaMailSenderImpl} is built per send: it is cheap next to an SMTP handshake, and it
 * carries no session state that could let one channel's credentials reach another's server.
 * STARTTLS, when chosen, is <em>required</em> — a server that does not offer it fails the
 * delivery rather than receiving the message in clear — and the server's identity is checked.
 * The subject is one line, so a rule name cannot add a header; the HTML part escapes every
 * value.
 */
@Component
public class EmailSender implements NotificationSender {

    private final ObjectMapper mapper;
    private final SettingsService settings;

    public EmailSender(ObjectMapper mapper, SettingsService settings) {
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public String kind() {
        return "EMAIL";
    }

    @Override
    public Result send(long deliveryId, String channelConfigJson, String password, String payloadJson) {
        EmailChannelConfig config;
        AlertMessage message;
        try {
            config = EmailChannelConfig.parse(channelConfigJson, mapper);
            message = AlertMessage.parse(payloadJson, mapper);
        } catch (RuntimeException e) {
            return Result.permanent("The channel or payload could not be read: " + e.getMessage());
        }
        if (config.host() == null || config.from() == null || config.to().isEmpty()) {
            return Result.permanent("Email channel needs a host, a sender and at least one recipient");
        }

        JavaMailSenderImpl sender = sender(config, password, settings.duration(AlertingSettings.EMAIL_TIMEOUT));
        try {
            MimeMessage mime = sender.createMimeMessage();
            compose(mime, config, message, deliveryId);
            sender.send(mime);
            return Result.ok();
        } catch (MailAuthenticationException e) {
            return Result.permanent("SMTP authentication failed: " + rootMessage(e));
        } catch (MailSendException e) {
            return classify(e);
        } catch (MailException e) {
            return Result.retryable("SMTP delivery failed: " + rootMessage(e));
        } catch (MessagingException e) {
            return Result.permanent("The message could not be composed: " + e.getMessage());
        }
    }

    static JavaMailSenderImpl sender(EmailChannelConfig config, String password, Duration timeout) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port() > 0 ? config.port() : defaultPort(config.security()));
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean auth = config.username() != null;
        if (auth) {
            sender.setUsername(config.username());
            sender.setPassword(password == null ? "" : password);
        }
        boolean implicitTls = EmailChannelConfig.TLS.equals(config.security());
        sender.setProtocol(implicitTls ? "smtps" : "smtp");
        String prefix = implicitTls ? "mail.smtps." : "mail.smtp.";
        String millis = Long.toString(timeout.toMillis());
        Properties props = sender.getJavaMailProperties();
        props.setProperty(prefix + "auth", Boolean.toString(auth));
        props.setProperty(prefix + "connectiontimeout", millis);
        props.setProperty(prefix + "timeout", millis);
        props.setProperty(prefix + "writetimeout", millis);
        if (EmailChannelConfig.STARTTLS.equals(config.security())) {
            props.setProperty("mail.smtp.starttls.enable", "true");
            props.setProperty("mail.smtp.starttls.required", "true");
            props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        }
        if (implicitTls) {
            props.setProperty("mail.smtps.ssl.checkserveridentity", "true");
        }
        return sender;
    }

    static void compose(MimeMessage mime, EmailChannelConfig config, AlertMessage message, long deliveryId)
            throws MessagingException {
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
        helper.setFrom(config.from());
        helper.setTo(config.to().toArray(String[]::new));
        String prefix = config.subjectPrefix() == null ? "" : config.subjectPrefix() + " ";
        helper.setSubject(AlertMessageFormatter.singleLine(prefix + AlertMessageFormatter.title(message)));
        helper.setText(AlertMessageFormatter.plainText(message), AlertMessageFormatter.html(message));
        // A stable id per delivery row lets a mail client thread a retried delivery with its first try.
        mime.setHeader("X-Artemis-Studio-Delivery", Long.toString(deliveryId));
    }

    /** Every recipient refused is permanent; a partial refusal or a connection failure is retried. */
    static Result classify(MailSendException e) {
        for (Exception failure : e.getFailedMessages().values()) {
            if (failure instanceof SendFailedException sfe
                    && sfe.getInvalidAddresses() != null
                    && sfe.getInvalidAddresses().length > 0
                    && (sfe.getValidUnsentAddresses() == null || sfe.getValidUnsentAddresses().length == 0)
                    && (sfe.getValidSentAddresses() == null || sfe.getValidSentAddresses().length == 0)) {
                return Result.permanent("Every recipient was refused: " + sfe.getMessage());
            }
        }
        return Result.retryable("SMTP delivery failed: " + rootMessage(e));
    }

    private static int defaultPort(String security) {
        return switch (security == null ? "" : security) {
            case EmailChannelConfig.TLS -> 465;
            case EmailChannelConfig.NONE -> 25;
            default -> 587;
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
