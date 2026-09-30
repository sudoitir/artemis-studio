package io.github.sudoitir.artemisstudio.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ApiContract} on a small document: what matches passes, and any drift from it is named. */
class ApiContractTest {

    private static final String DOC = """
            {"openapi":"3.1.0","info":{"title":"t","version":"v1"},
             "paths":{"/api/v1/things/{id}":{"get":{"responses":{
               "200":{"description":"ok","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Thing"}}}},
               "4XX":{"description":"bad","content":{"application/problem+json":{"schema":{"$ref":"#/components/schemas/Problem"}}}},
               "204":{"description":"none"}}}}},
             "components":{"schemas":{
               "Thing":{"type":"object","required":["id","name"],"properties":{
                 "id":{"type":"string"},"name":{"type":"string"},
                 "tags":{"type":"array","items":{"$ref":"#/components/schemas/Tag"}},
                 "note":{"type":["string","null"]}}},
               "Tag":{"type":"object","properties":{"label":{"type":"string"}}},
               "Problem":{"type":"object","properties":{"type":{"type":"string"},"status":{"type":"integer"}}}}}}
            """;

    private final ApiContract contract = new ApiContract(DOC);

    private List<String> get(int status, String type, String body) {
        return contract.check("GET", "/api/v1/things/{id}", status, type, body);
    }

    @Test
    void aResponseThatMatchesThePassesIncludingNullsAndNestedObjects() {
        assertThat(get(
                        200,
                        "application/json",
                        "{\"id\":\"1\",\"name\":\"a\",\"note\":null,\"tags\":[{\"label\":\"x\"}]}"))
                .isEmpty();
    }

    @Test
    void anUndocumentedFieldFails() {
        assertThat(get(200, "application/json", "{\"id\":\"1\",\"name\":\"a\",\"secret\":\"s\"}"))
                .singleElement()
                .asString()
                .contains("secret");
    }

    @Test
    void anUndocumentedFieldInANestedObjectFails() {
        assertThat(get(
                        200,
                        "application/json",
                        "{\"id\":\"1\",\"name\":\"a\",\"tags\":[{\"label\":\"x\",\"extra\":1}]}"))
                .singleElement()
                .asString()
                .contains("extra");
    }

    @Test
    void aMissingRequiredFieldAndAWrongTypeFail() {
        assertThat(get(200, "application/json", "{\"id\":\"1\"}")).isNotEmpty();
        assertThat(get(200, "application/json", "{\"id\":1,\"name\":\"a\"}")).isNotEmpty();
    }

    @Test
    void aProblemIsCheckedAgainstTheRangeResponse() {
        assertThat(get(404, "application/problem+json;charset=UTF-8", "{\"type\":\"t\",\"status\":404}"))
                .isEmpty();
        assertThat(get(404, "application/problem+json", "{\"type\":\"t\",\"trace\":\"stack\"}"))
                .isNotEmpty();
    }

    @Test
    void anUndocumentedOperationOrStatusFails() {
        assertThat(contract.check("GET", "/api/v1/other", 200, "application/json", "{}"))
                .singleElement()
                .asString()
                .contains("not in the document");
        assertThat(contract.check("POST", "/api/v1/things/{id}", 200, "application/json", "{}"))
                .isNotEmpty();
        assertThat(get(500, "application/json", "{}"))
                .singleElement()
                .asString()
                .contains("status");
    }

    @Test
    void anEmptyBodyIsNotChecked() {
        assertThat(get(204, null, "")).isEmpty();
    }

    @Test
    void aPathVariablesRegexAndTheVersionPlaceholderAreNormalised() {
        assertThat(ApiContract.template("/api/{version}/things/{id:\\d+}")).isEqualTo("/api/v1/things/{id}");
    }
}
