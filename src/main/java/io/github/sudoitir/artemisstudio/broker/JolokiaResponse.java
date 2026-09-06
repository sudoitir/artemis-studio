package io.github.sudoitir.artemisstudio.broker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One entry of a Jolokia response.
 *
 * <p>Phase 0 established two things this type exists to handle:
 *
 * <ul>
 *   <li>In a bulk response every entry carries its own {@code status}, and the
 *       HTTP status of the whole call is 200 even when some entries failed. Call
 *       {@link #ok()} per entry; never assume a 200 HTTP status means success.
 *   <li>The {@code value} of {@code listNetworkTopology()} and
 *       {@code listQueues(...)} is a JSON-encoded <em>string</em>. Use
 *       {@link #valueParsed(ObjectMapper)} to get the structured result.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JolokiaResponse(
        int status,
        JsonNode value,
        String error,
        @JsonProperty("error_type") String errorType) {

    public boolean ok() {
        return status == 200 && error == null;
    }

    /**
     * One attribute's value, whichever shape Jolokia answered a {@code read} in.
     *
     * <p>A {@code read} whose {@code attribute} is a bare string comes back as the
     * value itself; a {@code read} whose {@code attribute} is a <em>list</em> —
     * which is what {@link JolokiaRequest#read(String, String...)} always sends,
     * even for one name — comes back as a map keyed by attribute name. Reading
     * {@code value()} directly is therefore correct against a hand-written probe
     * and wrong against this client, which is how the mismatch survived: every
     * unit test stubs the scalar shape a live broker never sends here.
     */
    public JsonNode attribute(String name) {
        if (value == null) {
            return null;
        }
        return value.isObject() && value.has(name) ? value.get(name) : value;
    }

    /**
     * The {@code value}, parsed a second time when it is a JSON string (the
     * shape Artemis uses for {@code listNetworkTopology()} / {@code listQueues()}).
     * A non-string value is returned unchanged.
     */
    public JsonNode valueParsed(ObjectMapper mapper) {
        if (value != null && value.isTextual()) {
            try {
                return mapper.readTree(value.asText());
            } catch (Exception e) {
                throw new IllegalStateException("Jolokia value was a string but not JSON: " + value.asText(), e);
            }
        }
        return value;
    }
}
