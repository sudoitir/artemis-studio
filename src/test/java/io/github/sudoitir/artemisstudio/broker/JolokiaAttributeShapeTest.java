package io.github.sudoitir.artemisstudio.broker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jolokia answers a {@code read} in two shapes, and this client only ever
 * provokes the second one.
 *
 * <p>An {@code attribute} sent as a bare string comes back as the value itself;
 * an {@code attribute} sent as a <em>list</em> comes back as a map keyed by
 * attribute name. {@link JolokiaRequest#read(String, String...)} is varargs and
 * always serialises a list, so every single-attribute read this codebase makes
 * gets the map — while every unit test stubbing {@code {"value": 3}} gets the
 * scalar. That gap hid a live-broker failure in `purge?dryRun=true` (a 500 from
 * `asLong()` on an object), a browse total silently falling back to the page
 * size, and a CORE acceptor probe reading a missing `protocols` as "all
 * protocols". Both shapes are asserted here so a fix to one cannot regress the
 * other.
 */
class JolokiaAttributeShapeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JolokiaResponse parse(String json) {
        return JSON.readValue(json, JolokiaResponse.class);
    }

    @Test
    void readsTheMapShapeALiveBrokerReturnsForAnAttributeList() {
        JolokiaResponse response = parse("{\"status\":200,\"value\":{\"MessageCount\":3}}");
        assertThat(response.attribute("MessageCount").asLong()).isEqualTo(3);
    }

    @Test
    void stillReadsTheScalarShape() {
        JolokiaResponse response = parse("{\"status\":200,\"value\":3}");
        assertThat(response.attribute("MessageCount").asLong()).isEqualTo(3);
    }

    @Test
    void anObjectValuedAttributeIsUnwrappedRatherThanReturnedWhole() {
        // Acceptor "Parameters" is itself an object, so the wrapper and the payload
        // are both objects — the key is what tells them apart.
        JolokiaResponse response = parse("{\"status\":200,\"value\":{\"Parameters\":{\"protocols\":\"CORE\"}}}");
        assertThat(response.attribute("Parameters").get("protocols").asString()).isEqualTo("CORE");
    }

    @Test
    void anAbsentValueStaysAbsent() {
        assertThat(parse("{\"status\":200}").attribute("MessageCount")).isNull();
    }
}
