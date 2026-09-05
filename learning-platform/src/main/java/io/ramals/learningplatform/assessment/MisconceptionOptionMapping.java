package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): one {@code core.assessment_item_option_
 * misconception} row -- a specific wrong {@code SINGLE_CHOICE} option, tagged as evidence of a
 * specific {@link Misconception}. Deliberately not named or described using H4b's governed "probe"
 * vocabulary ({@code core.diagnostic_probe_relationship}/{@code core.diagnostic_probe_provenance},
 * {@link ProbeRelationshipResolver}): this mapping asserts no H4b probe relationship and is never
 * read by that resolver.
 */
public record MisconceptionOptionMapping(UUID itemVersionId, String optionId, UUID misconceptionId) {
}
