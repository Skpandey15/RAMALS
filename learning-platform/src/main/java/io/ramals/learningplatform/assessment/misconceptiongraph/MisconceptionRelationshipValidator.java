package io.ramals.learningplatform.assessment.misconceptiongraph;

import io.ramals.learningplatform.assessment.misconceptiongraph.MisconceptionRelationshipRepository.PrerequisiteCheck;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single deterministic validation locus for M2-ADR-033 authored graph edges (§13). Every rule
 * is a pure function of the request and already-persisted authored state; every refusal is a stable
 * {@link MisconceptionRelationshipReasonCode} raised as {@link MisconceptionGraphValidationException}.
 * Fail-closed: it never auto-heals, never canonicalises away a self-edge, and never silently
 * resolves a duplicate.
 *
 * <p>These checks are defence-in-depth in front of the database's own constraints and triggers, not
 * a substitute for them -- the database remains the final concurrency boundary (M2-ADR-033 §21).
 */
@Component
public class MisconceptionRelationshipValidator {

  private final MisconceptionRelationshipRepository repository;

  public MisconceptionRelationshipValidator(MisconceptionRelationshipRepository repository) {
    this.repository = repository;
  }

  /**
   * Validates an authored {@code MISCONCEPTION_RELATED} edge that will end in {@code targetStatus},
   * and returns the canonical {@code (a, b)} storage order to persist.
   *
   * @param source the authored source misconception; for {@link MisconceptionRelatedType#SPECIALISES}
   *     the more specific one
   * @param target the authored target misconception; for {@code SPECIALISES} the more general one
   * @param targetStatus the status the row will hold after this operation ({@code DRAFT} for a new
   *     draft, {@code PUBLISHED} for a publish)
   */
  public CanonicalMisconceptionPair validateRelationship(
      UUID source,
      UUID target,
      MisconceptionRelatedType relatedType,
      String rationale,
      MisconceptionGraphStatus targetStatus) {

    if (source == null) {
      throw refuse(MisconceptionRelationshipReasonCode.SOURCE_REQUIRED);
    }
    if (target == null) {
      throw refuse(MisconceptionRelationshipReasonCode.TARGET_REQUIRED);
    }
    if (relatedType == null) {
      throw refuse(MisconceptionRelationshipReasonCode.RELATIONSHIP_TYPE_REQUIRED);
    }
    if (isBlank(rationale)) {
      throw refuse(MisconceptionRelationshipReasonCode.RATIONALE_REQUIRED);
    }
    if (source.equals(target)) {
      throw refuse(MisconceptionRelationshipReasonCode.SELF_RELATIONSHIP_NOT_ALLOWED);
    }

    MisconceptionGraphStatus sourceStatus =
        repository.misconceptionStatus(source).orElseThrow(
            () -> refuse(MisconceptionRelationshipReasonCode.SOURCE_MISCONCEPTION_NOT_FOUND));
    MisconceptionGraphStatus targetMisconceptionStatus =
        repository.misconceptionStatus(target).orElseThrow(
            () -> refuse(MisconceptionRelationshipReasonCode.TARGET_MISCONCEPTION_NOT_FOUND));

    CanonicalMisconceptionPair pair =
        CanonicalMisconceptionPair.of(relatedType, source, target);

    if (repository.relationshipExists(pair, relatedType)) {
      throw refuse(MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
    }

    if (relatedType == MisconceptionRelatedType.SPECIALISES
        && repository.wouldSpecialisationCreateCycle(source, target)) {
      throw refuse(MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
    }

    if (targetStatus == MisconceptionGraphStatus.PUBLISHED
        && (sourceStatus != MisconceptionGraphStatus.PUBLISHED
            || targetMisconceptionStatus != MisconceptionGraphStatus.PUBLISHED)) {
      throw refuse(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
    }

    return pair;
  }

  /**
   * Validates an authored {@code MISCONCEPTION_PREREQUISITE_LINK} that will end in
   * {@code targetStatus}.
   */
  public void validatePrerequisiteLink(
      UUID misconceptionId,
      UUID prerequisiteSkillId,
      String rationale,
      MisconceptionGraphStatus targetStatus) {

    if (misconceptionId == null) {
      throw refuse(MisconceptionRelationshipReasonCode.SOURCE_REQUIRED);
    }
    if (prerequisiteSkillId == null) {
      throw refuse(MisconceptionRelationshipReasonCode.TARGET_REQUIRED);
    }
    if (isBlank(rationale)) {
      throw refuse(MisconceptionRelationshipReasonCode.RATIONALE_REQUIRED);
    }

    MisconceptionGraphStatus misconceptionStatus =
        repository.misconceptionStatus(misconceptionId).orElseThrow(
            () -> refuse(MisconceptionRelationshipReasonCode.SOURCE_MISCONCEPTION_NOT_FOUND));
    if (!repository.skillExists(prerequisiteSkillId)) {
      throw refuse(MisconceptionRelationshipReasonCode.PREREQUISITE_SKILL_NOT_FOUND);
    }

    if (repository.prerequisiteLinkExists(misconceptionId, prerequisiteSkillId)) {
      throw refuse(MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
    }

    if (targetStatus == MisconceptionGraphStatus.PUBLISHED) {
      if (misconceptionStatus != MisconceptionGraphStatus.PUBLISHED) {
        throw refuse(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
      }
      PrerequisiteCheck check =
          repository.checkCurriculumPrerequisite(misconceptionId, prerequisiteSkillId);
      if (check == PrerequisiteCheck.OWNING_SKILL_UNRESOLVABLE) {
        throw refuse(MisconceptionRelationshipReasonCode.MISCONCEPTION_OWNING_SKILL_UNRESOLVABLE);
      }
      if (check == PrerequisiteCheck.NOT_A_PREREQUISITE) {
        throw refuse(
            MisconceptionRelationshipReasonCode.PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE);
      }
    }
  }

  /**
   * Re-validates a stored DRAFT related edge before it is published: both endpoints must be
   * PUBLISHED, and a {@code SPECIALISES} edge must still not close a cycle (a concurrent insert
   * elsewhere could have created a path). The duplicate check is deliberately skipped -- the edge
   * being published is itself the one row that matches.
   */
  public void validateRelationshipPublish(MisconceptionRelationship edge) {
    boolean bothPublished =
        repository.misconceptionStatus(edge.misconceptionAId())
                .filter(status -> status == MisconceptionGraphStatus.PUBLISHED).isPresent()
            && repository.misconceptionStatus(edge.misconceptionBId())
                .filter(status -> status == MisconceptionGraphStatus.PUBLISHED).isPresent();
    if (!bothPublished) {
      throw refuse(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
    }
    if (edge.relatedType() == MisconceptionRelatedType.SPECIALISES
        && repository.wouldSpecialisationCreateCycle(
            edge.misconceptionAId(), edge.misconceptionBId())) {
      throw refuse(MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
    }
  }

  /**
   * Re-validates a stored DRAFT prerequisite link before it is published: the misconception must be
   * PUBLISHED and a matching {@code core.skill_prerequisite} row must exist for its owning skill
   * and curriculum version.
   */
  public void validatePrerequisiteLinkPublish(MisconceptionPrerequisiteLink link) {
    boolean misconceptionPublished =
        repository.misconceptionStatus(link.misconceptionId())
            .filter(status -> status == MisconceptionGraphStatus.PUBLISHED).isPresent();
    if (!misconceptionPublished) {
      throw refuse(MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
    }
    PrerequisiteCheck check =
        repository.checkCurriculumPrerequisite(
            link.misconceptionId(), link.prerequisiteSkillId());
    if (check == PrerequisiteCheck.OWNING_SKILL_UNRESOLVABLE) {
      throw refuse(MisconceptionRelationshipReasonCode.MISCONCEPTION_OWNING_SKILL_UNRESOLVABLE);
    }
    if (check == PrerequisiteCheck.NOT_A_PREREQUISITE) {
      throw refuse(
          MisconceptionRelationshipReasonCode.PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE);
    }
  }

  private static MisconceptionGraphValidationException refuse(
      MisconceptionRelationshipReasonCode reasonCode) {
    return new MisconceptionGraphValidationException(reasonCode);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
