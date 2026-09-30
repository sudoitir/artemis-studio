package io.github.sudoitir.artemisstudio.kernel.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller that is not part of the versioned REST API. Every other Studio controller
 * is served under {@code /api/{version}} (ADR-0143) and maps only what follows it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Unversioned {}
