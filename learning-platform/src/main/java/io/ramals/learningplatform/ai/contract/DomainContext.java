package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Which learning domain a request is about, and which published curriculum version defines it.
 *
 * <p>Every field is resolved from authoritative state by {@code DomainContextAssembler}; none is
 * supplied by a caller or inferred by an agent. That is the point of the type: the platform is
 * domain-neutral by construction, and an agent that assumes a domain would quietly make the first
 * one permanent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomainContext(
    String domainCode,
    DomainType domainType,
    String curriculumVersion) {
}
