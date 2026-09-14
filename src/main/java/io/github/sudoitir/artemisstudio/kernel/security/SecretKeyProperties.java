package io.github.sudoitir.artemisstudio.kernel.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The base64 key that encrypts stored broker credentials (ADR-0009). Startup fails without it. */
@ConfigurationProperties(prefix = "artemis-studio")
public record SecretKeyProperties(String secretKey) {}
