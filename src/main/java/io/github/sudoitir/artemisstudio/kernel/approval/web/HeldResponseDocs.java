package io.github.sudoitir.artemisstudio.kernel.approval.web;

import io.github.sudoitir.artemisstudio.kernel.gate.GateContext;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOutcome;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.lang.annotation.Annotation;
import java.util.List;
import org.springdoc.core.customizers.GlobalOperationComponentsCustomizer;
import org.springdoc.core.utils.SpringDocAnnotationsUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Adds the held {@code 202} to every endpoint marked {@link HeldResponse}, next to the responses the endpoint
 * documents or springdoc infers from its return type, so the usual success response stays in the contract. An endpoint
 * whose own success is a {@code 202} gets one {@code 202} that is either its body or the held outcome.
 */
@Component
class HeldResponseDocs implements GlobalOperationComponentsCustomizer {

    static final String DESCRIPTION =
            "Held for approval: the operation has not run. X-Studio-Held-Operation names the request.";

    /** Springdoc calls the variant with the components for this customizer. */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        return operation;
    }

    @Override
    public Operation customize(Operation operation, Components components, HandlerMethod handlerMethod) {
        if (!handlerMethod.hasMethodAnnotation(HeldResponse.class) || operation.getResponses() == null) {
            return operation;
        }
        Schema<?> held = SpringDocAnnotationsUtils.extractSchema(
                components, HeldOutcome.class, null, new Annotation[0], SpecVersion.V31);
        ApiResponse accepted = operation.getResponses().get("202");
        if (accepted == null) {
            operation.getResponses().addApiResponse("202", heldOnly(held));
        } else {
            alsoHeld(accepted, held);
        }
        return operation;
    }

    private static ApiResponse heldOnly(Schema<?> held) {
        return new ApiResponse()
                .description(DESCRIPTION)
                .addHeaderObject(GateContext.HELD_HEADER, heldHeader())
                .content(new Content()
                        .addMediaType(
                                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                new MediaType().schema(held)));
    }

    /** An endpoint whose own success is a 202 answers either its own body or the held outcome. */
    private static void alsoHeld(ApiResponse accepted, Schema<?> held) {
        accepted.description(accepted.getDescription() + "; or held for approval: the operation has not run");
        accepted.addHeaderObject(GateContext.HELD_HEADER, heldHeader());
        if (accepted.getContent() == null || accepted.getContent().isEmpty()) {
            accepted.content(new Content()
                    .addMediaType(
                            org.springframework.http.MediaType.APPLICATION_JSON_VALUE, new MediaType().schema(held)));
            return;
        }
        accepted.getContent()
                .values()
                .forEach(media -> media.schema(new Schema<>().oneOf(List.of(media.getSchema(), held))));
    }

    private static Header heldHeader() {
        return new Header()
                .description("The held operation's id, when the operation was held.")
                .schema(new StringSchema().format("uuid"));
    }
}
