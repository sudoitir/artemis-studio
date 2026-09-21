# ADR-0096: Primitive request fields are required in the contract

- **Status**: accepted
- **Date**: 2026-09-21
- **Deciders**: Artemis Studio maintainers

## Context

Every bulk preview failed with 400 before the service ran (ADR-0093). The cause was a
contract that said one thing while the server enforced another:

- `BulkPreviewRequest.disconnectConsumers` is a primitive `boolean`.
- Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default. A body that omits a
  primitive is refused with `MismatchedInputException`, which Spring answers as
  `HttpMessageNotReadableException`, which is a 400.
- springdoc listed the field as optional, because nothing annotated it
  `requiredMode = REQUIRED`. `openapi-typescript` therefore generated
  `disconnectConsumers?: boolean`, and the frontend legally left it out.

No test saw it. The service tests call `BulkService` directly, and the MSW preview
handler never read the request body. The typecheck, the one guard between the two
sides, was checking against a contract the server does not keep.

The same gap existed on every record with a primitive component that was not annotated
by hand: 27 schemas changed when the rule below was applied.

## Decision

1. **A primitive property is required in the OpenAPI document**, for requests and
   responses alike. A `PropertyCustomizer` in `kernel.core` (`OpenApiConfig`) adds every
   property whose Java type is primitive to its parent schema's `required` list. It
   covers all endpoints at once. Nobody has to remember an annotation.
2. **The rule matches what Jackson does.** A request missing a primitive is refused, and
   a response always carries a primitive. So the generated TypeScript type makes the
   field non-optional in both directions, and the typecheck fails where the server
   would.
3. **A field that may truly be absent is a reference type** (`Boolean`, `Integer`), with
   `@Schema(nullable = true)` and a server-side default. A primitive is never used to
   mean "optional, default false".
4. **A reference field the server cannot do without carries `@NotNull`** as well as
   `requiredMode = REQUIRED`. Its absence is then a 400 validation problem rather than
   a `NullPointerException` in the service.
5. **Each endpoint is tested at the HTTP level** with the payloads the frontend sends,
   not only through the service. `BulkControllerTest` is the pattern, and
   `OpenApiSnapshotTest` asserts the rule on the bulk request schemas.

## Consequences

- The contract matches the server. An omitted primitive now fails `npm run typecheck`
  instead of failing in production with a 400.
- Response types got stricter too. Test fixtures that built a DTO without a primitive
  field no longer compile and have to state the field; four such fixtures were fixed.
- The rule is global. A class serialised with `@JsonInclude(NON_DEFAULT)` would omit a
  default primitive and contradict it. No API DTO does that today. One that does must
  use a reference type instead.
- Relaxing `FAIL_ON_NULL_FOR_PRIMITIVES` later would make the contract stricter than
  the server. That is safe, but the rule would then need revisiting.

## Alternatives considered

- **Annotate each primitive `requiredMode = REQUIRED` by hand.** This is what most
  records already did. It is exactly the discipline that failed here, and nothing
  catches the next omission.
- **Disable `FAIL_ON_NULL_FOR_PRIMITIVES`**, so a missing primitive becomes its
  default. This hides client bugs. A missing `override` or `disconnectConsumers` would
  silently mean `false` on a destructive endpoint, and the contract would still say
  optional for a field whose absence changes behaviour.
- **Make the frontend send the field and change nothing else.** This fixes one call
  and leaves every other endpoint with the same gap.
