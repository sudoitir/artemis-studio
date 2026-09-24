package io.github.sudoitir.artemisstudio.feature.alerting;

/** A notification channel did not accept a delivery Studio made on an operator's request. */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }
}
