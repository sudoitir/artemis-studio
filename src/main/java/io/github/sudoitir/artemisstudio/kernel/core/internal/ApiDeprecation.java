package io.github.sudoitir.artemisstudio.kernel.core.internal;

import java.net.URI;
import java.time.ZonedDateTime;
import org.springframework.http.HttpMethod;

/**
 * One deprecation of the REST API: a version, or one endpoint of it when {@code method} and
 * {@code path} are set. {@code path} is the concrete document path, for example
 * {@code /api/v1/clusters/{clusterId}/x}. A matching response carries {@code Deprecation},
 * {@code Sunset} and {@code Link} headers, and the document marks the operation deprecated.
 * Declare them as beans in {@link ApiDeprecations}; at most one per version, because Spring keeps one
 * spec per version.
 */
record ApiDeprecation(
        String version, HttpMethod method, String path, ZonedDateTime deprecated, ZonedDateTime sunset, URI link) {}
