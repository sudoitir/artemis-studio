package io.github.sudoitir.artemisstudio.feature.alerting.web;

import io.github.sudoitir.artemisstudio.feature.alerting.NotificationDeliveryException;
import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A channel that refused a test notification is the receiving system's failure, so a 502. */
@RestControllerAdvice
class AlertingProblemAdvice {

    @ExceptionHandler(NotificationDeliveryException.class)
    ProblemDetail onNotificationDelivery(NotificationDeliveryException e) {
        return Problems.of(
                HttpStatus.BAD_GATEWAY, "notification-delivery-failed", "Notification delivery failed", e.getMessage());
    }
}
