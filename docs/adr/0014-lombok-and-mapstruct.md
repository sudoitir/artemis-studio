# ADR-0014: Lombok for boilerplate, MapStruct for layer-to-layer mapping

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0011 moved persistence to JPA entities. Entities need a no-arg constructor,
getters, and equality; the web layer (Phase 1, group 8) needs to convert three
shapes — JPA entities, domain records (`domain/topology/*`), and API view
records — into one another. Written by hand that is hundreds of lines of
accessors and null-checked field copies, and every new column is edited in
several places.

The project already runs an annotation processor at compile time
(`spring-boot-configuration-processor`) and a source formatter bound to
`process-sources` (Spotless / Palantir).

## Decision

- **Lombok** for entity and carrier boilerplate only:
  - Entities: `@Getter`, `@NoArgsConstructor(access = PROTECTED)`,
    `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` with the id included.
    Hand-written factory methods and state-transition methods stay — behaviour is
    not generated.
  - Spring components: `@RequiredArgsConstructor` over `final` fields instead of a
    hand-written injection constructor. `@Slf4j` for loggers.
  - No `@Data`, no `@Builder` on entities, no `@Setter` on entities (state
    changes go through named methods).
- **MapStruct** for every entity ↔ domain ↔ DTO conversion:
  - One `@MapperConfig` (`mapper/CentralMapperConfig`) fixes the shared settings:
    `componentModel = "spring"`, `injectionStrategy = CONSTRUCTOR`,
    `unmappedTargetPolicy = ERROR` (a new field that nobody mapped fails the
    build), `nullValuePropertyMappingStrategy = IGNORE`.
  - Mappers are Spring beans; derived fields use `expression` / `@AfterMapping`;
    domain assembly that needs the split-brain ratchet stays in
    `HaStateEvaluator` (stateful, not a mapping).
- **Build wiring**: `maven-compiler-plugin` `annotationProcessorPaths` lists, in
  order, `lombok`, `lombok-mapstruct-binding`, and `mapstruct-processor`. Lombok
  version is managed by the Spring Boot BOM; MapStruct (`1.6.3`) and the binding
  (`0.2.0`) are pinned as properties. (No `spring-boot-configuration-processor` —
  it was never on the build and config metadata is not needed yet.)
- Generated mappers land in `target/generated-sources` and are **not** formatted
  or lint-checked by Spotless (it is bound to `src/main/java` only). `lombok.config`
  at the repo root sets `config.stopBubbling = true` and
  `lombok.addLombokGeneratedAnnotation = true` so coverage and static analysis
  skip generated members.

## Consequences

- A column added to a table is: one field on the entity, one field on the DTO,
  and — because `unmappedTargetPolicy = ERROR` — a compile error until the mapper
  is updated. The compiler enforces the mapping is complete.
- Two more annotation processors on the compile path. Measured cost is small and
  the processors are well established.
- Lombok is a language-level dependency: it must be on every contributor's IDE
  (the IntelliJ/Eclipse Lombok plugin). Documented in the README setup section.
- Debugging steps through generated MapStruct code, which is plain, readable Java
  — acceptable, and better than hand-copied field assignments.

## Alternatives considered

- **Hand-written mappers and accessors.** The status quo. Rejected: repetitive,
  and a missed field copy is a silent bug rather than a build failure.
- **Java records for entities.** JPA needs a mutable no-arg bean; records do not
  fit. Records remain the shape for domain and DTO types, which MapStruct maps to
  via their canonical constructor.
- **Lombok `@Data` on entities.** Pulls in `@Setter`, `@ToString`, and a
  collection-touching `equals`/`hashCode` — all hazards on a JPA entity.
  Rejected in favour of the narrow annotation set above.
