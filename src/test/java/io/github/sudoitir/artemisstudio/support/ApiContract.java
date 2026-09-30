package io.github.sudoitir.artemisstudio.support;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The API document as a test oracle (api-contract spec, ADR-0149): checks a response body against the schema
 * the document gives its operation and status. Every object schema is closed with
 * {@code unevaluatedProperties: false}, so a field the document does not describe is a violation, as is a
 * missing required field or a wrong type. {@link ApiContractConfig} feeds it every MockMvc response.
 */
public final class ApiContract {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** Keywords whose value is a map of name to schema. */
    private static final List<String> SCHEMA_MAPS = List.of("properties", "patternProperties", "$defs");
    /** Keywords whose value is one schema. */
    private static final List<String> SCHEMAS = List.of(
            "items",
            "additionalProperties",
            "unevaluatedProperties",
            "not",
            "if",
            "then",
            "else",
            "contains",
            "propertyNames");
    /** Keywords whose value is a list of schemas. */
    private static final List<String> SCHEMA_LISTS = List.of("allOf", "anyOf", "oneOf", "prefixItems");

    private static volatile ApiContract committed;

    private final JsonNode paths;
    private final JsonNode components;
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

    public ApiContract(String openApiJson) {
        JsonNode document = JSON.readTree(openApiJson);
        this.paths = document.path("paths");
        this.components = close(document.path("components"), true);
    }

    /** The document committed at {@code web/openapi.json}, read once. */
    public static ApiContract committed() {
        if (committed == null) {
            try {
                committed = new ApiContract(Files.readString(Path.of("web", "openapi.json")));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return committed;
    }

    /**
     * @param method the HTTP method
     * @param pattern the handler's path pattern, {@code /api/v1/clusters/{id}}
     * @param status the response status
     * @param contentType the response's Content-Type, or null
     * @param body the response body; an empty one is not checked
     * @return what is wrong with the response, empty when it matches the document
     */
    public List<String> check(String method, String pattern, int status, String contentType, String body) {
        String label = method + " " + pattern + " -> " + status;
        JsonNode operation = paths.path(template(pattern)).path(method.toLowerCase());
        if (operation.isMissingNode()) {
            return List.of(label + ": the operation is not in the document");
        }
        JsonNode responses = operation.path("responses");
        JsonNode response = responses.path(Integer.toString(status));
        if (response.isMissingNode()) {
            response = responses.path(status / 100 + "XX");
        }
        if (response.isMissingNode()) {
            response = responses.path("default");
        }
        if (response.isMissingNode()) {
            return List.of(label + ": the status is not in the document");
        }
        JsonNode schema = schemaOf(response.path("content"), contentType);
        if (schema.isMissingNode() || body == null || body.isBlank()) {
            return List.of();
        }
        List<Error> errors = schemas.computeIfAbsent(schema.toString(), s -> registry.getSchema(root(schema)))
                .validate(body, InputFormat.JSON);
        List<String> violations = new ArrayList<>();
        for (Error error : errors) {
            violations.add(label + ": " + error.getInstanceLocation() + " " + error.getMessage());
        }
        return violations;
    }

    /** {@code /api/{version}/x} is {@code /api/v1/x}; a path variable's regex is dropped. */
    static String template(String pattern) {
        return pattern.replace("/api/{version}", "/api/v1").replaceAll("\\{(\\w+):[^}]*}", "{$1}");
    }

    private static JsonNode schemaOf(JsonNode content, String contentType) {
        String type = contentType == null ? "" : contentType.split(";")[0].trim();
        for (String candidate : new String[] {type, "application/json", "application/problem+json", "*/*"}) {
            if (content.has(candidate)) {
                return content.path(candidate).path("schema");
            }
        }
        return JSON.missingNode();
    }

    private String root(JsonNode schema) {
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", DIALECT);
        root.set("components", components);
        ArrayNode all = root.putArray("allOf");
        all.add(close(schema, false));
        return root.toString();
    }

    private static JsonNode close(JsonNode node, boolean componentsRoot) {
        if (componentsRoot) {
            if (!(node.deepCopy() instanceof ObjectNode copy)) {
                return JSON.createObjectNode();
            }
            JsonNode schemas = copy.path("schemas");
            if (schemas instanceof ObjectNode map) {
                map.propertyNames().forEach(name -> map.set(name, close(map.get(name), false)));
            }
            return copy;
        }
        if (!(node instanceof ObjectNode schema)) {
            return node;
        }
        ObjectNode copy = (ObjectNode) schema.deepCopy();
        for (String keyword : SCHEMA_MAPS) {
            if (copy.get(keyword) instanceof ObjectNode map) {
                map.propertyNames().forEach(name -> map.set(name, close(map.get(name), false)));
            }
        }
        for (String keyword : SCHEMAS) {
            if (copy.has(keyword)) {
                copy.set(keyword, close(copy.get(keyword), false));
            }
        }
        for (String keyword : SCHEMA_LISTS) {
            if (copy.get(keyword) instanceof ArrayNode list) {
                for (int i = 0; i < list.size(); i++) {
                    list.set(i, close(list.get(i), false));
                }
            }
        }
        if (copy.has("properties") && !copy.has("additionalProperties") && !copy.has("unevaluatedProperties")) {
            copy.put("unevaluatedProperties", false);
        }
        return copy;
    }
}
