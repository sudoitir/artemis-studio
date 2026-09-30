package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * A real Tomcat on a random port, because MockMvc bypasses the connector's {@code RemoteIpValve}
 * and so cannot tell whether a forwarded address was honoured (ADR-0144). Each subclass fixes who
 * counts as a trusted proxy; the test client is always the loopback peer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class ForwardedAddressTestBase extends PostgresIntegrationTest {

    /** More than the per-username-and-source limit of five, so the limiter's key decides the outcome. */
    static final int ATTEMPTS = 8;

    @LocalServerPort
    int port;

    /**
     * Failed sign-ins of one unknown username, each claiming a different client through
     * {@code X-Forwarded-For}; the statuses in order.
     */
    List<Integer> failedLoginsWithForgedAddresses() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String csrf = client.get()
                .uri("/api/v1/auth/providers")
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getFirst(HttpHeaders.SET_COOKIE)
                .split(";")[0]
                .substring("XSRF-TOKEN=".length());
        String username = "forged-" + UUID.randomUUID();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            int status = client.post()
                    .uri("/api/v1/auth/login")
                    .header("X-Forwarded-For", "198.51.100." + (i + 1))
                    .header("X-XSRF-TOKEN", csrf)
                    .header(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"username\":\"%s\",\"password\":\"wrong\"}".formatted(username))
                    .exchange((request, response) -> response.getStatusCode().value());
            statuses.add(status);
        }
        return statuses;
    }
}
