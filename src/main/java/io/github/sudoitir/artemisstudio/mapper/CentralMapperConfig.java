package io.github.sudoitir.artemisstudio.mapper;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * Shared MapStruct settings (ADR-0014). Referenced by every mapper via
 * {@code @Mapper(config = CentralMapperConfig.class)}.
 *
 * <ul>
 *   <li>{@code componentModel = "spring"} — mappers are injectable beans.
 *   <li>{@code unmappedTargetPolicy = ERROR} — a target field nobody maps fails
 *       the build, so a new column cannot silently go unmapped.
 *   <li>{@code injectionStrategy = CONSTRUCTOR} — generated mappers take their
 *       collaborators as constructor args, matching the rest of the codebase.
 *   <li>{@code nullValuePropertyMappingStrategy = IGNORE} — updates leave a target
 *       property untouched when the source is null.
 * </ul>
 */
@MapperConfig(
        componentModel = "spring",
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CentralMapperConfig {}
