package io.github.sudoitir.artemisstudio.kernel.gate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that runs a gated operation (ADR-0179): with an approval provider armed it may answer {@code 202}
 * with {@link GateContext#HELD_HEADER} and a {@link HeldOutcome} instead of its usual result, and the operation has
 * not run. The API document adds that {@code 202} next to the endpoint's own responses. An endpoint that already
 * answers {@code 202} declares both shapes itself, as a {@code oneOf} with {@link HeldOutcome}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HeldResponse {}
