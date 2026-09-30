package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.kernel.core.Branding;
import io.github.sudoitir.artemisstudio.kernel.core.StudioProperties;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * Passkeys through Spring Security's WebAuthn support (ADR-0143), called from Studio's own JSON
 * endpoints rather than its filters, which assume a form or filter-driven login. The repositories
 * are always there, so an existing passkey can be listed and removed; the relying party exists only
 * once {@code artemis-studio.public-url} says where Studio is reached, because a passkey is bound to
 * that address's host.
 */
@Configuration(proxyBeanMethods = false)
class WebAuthnConfiguration {

    @Bean
    PublicKeyCredentialUserEntityRepository passkeyUserEntities(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository passkeyCredentials(JdbcOperations jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }

    /**
     * The relying party id is the host, and the only origin allowed is the address itself, so a
     * passkey answers to Studio and to nothing that merely shares its host name.
     */
    @Bean
    @ConditionalOnExpression("!'${artemis-studio.public-url:}'.isBlank()")
    WebAuthnRelyingPartyOperations passkeyRelyingParty(
            PublicKeyCredentialUserEntityRepository userEntities,
            UserCredentialRepository credentials,
            StudioProperties studio) {
        URI address = URI.create(studio.publicUrl());
        PublicKeyCredentialRpEntity relyingParty = PublicKeyCredentialRpEntity.builder()
                .id(rpId(address))
                .name(Branding.PRODUCT_NAME)
                .build();
        return new Webauthn4JRelyingPartyOperations(userEntities, credentials, relyingParty, Set.of(origin(address)));
    }

    /** The host as a browser writes it: lower case. A passkey's relying party id is compared to it exactly. */
    static String rpId(URI address) {
        return address.getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * The origin a browser reports for the address: scheme and host in lower case, and the port only when it is not
     * the scheme's default, because the reported origin leaves that out and is compared to this exactly.
     */
    static String origin(URI address) {
        String scheme = address.getScheme().toLowerCase(Locale.ROOT);
        int port = address.getPort();
        boolean standard = port == -1 || port == ("https".equals(scheme) ? 443 : 80);
        return scheme + "://" + rpId(address) + (standard ? "" : ":" + port);
    }
}
