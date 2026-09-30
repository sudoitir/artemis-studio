package io.github.sudoitir.artemisstudio.kernel.core.internal;

import org.springframework.context.annotation.Configuration;

/**
 * Where the REST API's deprecations are declared, as {@link ApiDeprecation} {@code @Bean}s. Nothing is
 * deprecated before the stable release (ADR-0149), so the list is empty.
 */
@Configuration
class ApiDeprecations {}
