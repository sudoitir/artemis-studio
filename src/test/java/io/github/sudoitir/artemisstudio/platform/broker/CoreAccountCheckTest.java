package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;

class CoreAccountCheckTest {

    @Test
    void aSecurityExceptionMeansTheBrokerRefusedTheAccount() {
        assertThat(CoreAccountCheck.resultOf(new JMSSecurityException("AMQ229031: Unable to validate user")))
                .isEqualTo(AccountResult.REJECTED);
    }

    @Test
    void aSecurityExceptionBehindAWrapperStillMeansRefused() {
        assertThat(CoreAccountCheck.resultOf(new RuntimeException("wrapped", new JMSSecurityException("denied"))))
                .isEqualTo(AccountResult.REJECTED);
    }

    @Test
    void aFailureToConnectSaysNothingOfTheAccount() {
        JMSException cannotConnect = new JMSException("AMQ219007: Cannot connect");
        cannotConnect.initCause(new ConnectException("Connection refused"));

        assertThat(CoreAccountCheck.resultOf(cannotConnect)).isEqualTo(AccountResult.UNREACHABLE);
    }

    @Test
    void aCheckWithNoCoreUrlIsNotTried() {
        CoreAccountCheck check = new CoreAccountCheck(null);

        assertThat(check.check(null, CoreConnectionSettings.anonymous(null))).isEqualTo(AccountResult.NOT_TRIED);
    }
}
