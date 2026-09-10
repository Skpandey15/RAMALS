package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only write path for M2-ADR-033 authored misconception graph edges. Deterministic Java, one
 * transaction per authored action: {@link MisconceptionRelationshipValidator} checks the request
 * against already-persisted authored state, then {@link MisconceptionRelationshipRepository}
 * persists it, and any database constraint/trigger violation that slipped through a concurrency
 * race is translated back into the same stable {@link MisconceptionRelationshipReasonCode}
 * (M2-ADR-033 §13/§21).
 *
 * <p><b>Authored-data authority (M2-ADR-033 §14):</b> this is a plain Spring service in the
 * authoritative Java curriculum/diagnostic domain. Nothing in {@code ai/}, {@code orchestration/},
 * an LLM, LangGraph, MCP, {@code AIProposalEnvelope}, or {@code DiagnosticProbeProposal} can reach
 * it -- an architecture guardrail test locks that down. AI may read graph knowledge in a later
 * step; it never authors or mutates it.
 *
 * <p><b>No diagnostic runtime integration (M2-ADR-033 §6/§15):</b> this service is called by
 * nothing at runtime. It is not wired into {@code DiagnosticService},
 * {@code DiagnosticSubmissionService}, {@code DIAGNOSTIC_SELECTION_V1}-{@code V5},
 * {@code ProbeRelationshipResolver}, or any H6/H7 read. Graph data is stored and validated only.
 */
@Service
public class MisconceptionRelationshipService {

  private final MisconceptionRelationshipRepository repository;
  private final MisconceptionRelationshipValidator validator;

  public MisconceptionRelationshipService(
      MisconceptionRelationshipRepository repository,
      MisconceptionRelationshipValidator validator) {
    this.repository = repository;
    this.validator = validator;
  }

  // -- MISCONCEPTION_RELATED -------------------------------------------------------------------

  /**
   * Authors a DRAFT {@code MISCONCEPTION_RELATED} edge and returns its id. For a symmetric type the
   * pair is stored under its canonical ordering; for {@link MisconceptionRelatedType#SPECIALISES}
   * the authored {@code source -> target} direction is kept.
   *
   * @throws MisconceptionGraphValidationException with a stable reason code if the edge is refused
   */
  @Transactional
  public UUID authorDraftRelationship(
      UUID source, UUID target, MisconceptionRelatedType relatedType, String rationale) {
    CanonicalMisconceptionPair pair =
        validator.validateRelationship(
            source, target, relatedType, rationale, MisconceptionGraphStatus.DRAFT);
    try {
      return repository.insertDraftRelationship(pair, relatedType, rationale);
    } catch (DataIntegrityViolationException violation) {
      throw translate(violation);
    }
  }

  /**
   * DRAFT -> PUBLISHED for a related edge. Idempotent: publishing an already-published edge is a
   * no-op, never an error.
   *
   * @throws MisconceptionGraphEdgeNotFoundException if no such edge exists
   * @throws MisconceptionGraphValidationException with a stable reason code if publication is refused
   */
  @Transactional
  public void publishRelationship(UUID id) {
    MisconceptionRelationship edge =
        repository.findRelationshipById(id)
            .orElseThrow(() -> new MisconceptionGraphEdgeNotFoundException(id));
    if (edge.isPublished()) {
      return;
    }
    validator.validateRelationshipPublish(edge);
    try {
      repository.publishRelationship(id);
    } catch (DataIntegrityViolationException violation) {
      throw translate(violation);
    }
  }

  @Transactional(readOnly = true)
  public Optional<MisconceptionRelationship> findRelationship(UUID id) {
    return repository.findRelationshipById(id);
  }

  // -- MISCONCEPTION_PREREQUISITE_LINK -------------------------------------------------------

  /**
   * Authors a DRAFT {@code MISCONCEPTION_PREREQUISITE_LINK} from a misconception to a curriculum
   * prerequisite skill and returns its id. The curriculum-prerequisite existence rule is checked
   * fully only at publish time (a DRAFT link may be authored ahead of its curriculum context).
   *
   * @throws MisconceptionGraphValidationException with a stable reason code if the link is refused
   */
  @Transactional
  public UUID authorDraftPrerequisiteLink(
      UUID misconceptionId, UUID prerequisiteSkillId, String rationale) {
    validator.validatePrerequisiteLink(
        misconceptionId, prerequisiteSkillId, rationale, MisconceptionGraphStatus.DRAFT);
    try {
      return repository.insertDraftPrerequisiteLink(misconceptionId, prerequisiteSkillId, rationale);
    } catch (DataIntegrityViolationException violation) {
      throw translate(violation);
    }
  }

  /**
   * DRAFT -> PUBLISHED for a prerequisite link. Idempotent. Publication requires the misconception
   * to be PUBLISHED and a matching {@code core.skill_prerequisite} row to exist for its owning
   * skill and curriculum version.
   *
   * @throws MisconceptionGraphEdgeNotFoundException if no such link exists
   * @throws MisconceptionGraphValidationException with a stable reason code if publication is refused
   */
  @Transactional
  public void publishPrerequisiteLink(UUID id) {
    MisconceptionPrerequisiteLink link =
        repository.findPrerequisiteLinkById(id)
            .orElseThrow(() -> new MisconceptionGraphEdgeNotFoundException(id));
    if (link.isPublished()) {
      return;
    }
    validator.validatePrerequisiteLinkPublish(link);
    try {
      repository.publishPrerequisiteLink(id);
    } catch (DataIntegrityViolationException violation) {
      throw translate(violation);
    }
  }

  @Transactional(readOnly = true)
  public Optional<MisconceptionPrerequisiteLink> findPrerequisiteLink(UUID id) {
    return repository.findPrerequisiteLinkById(id);
  }

  // -- database-race translation ----------------------------------------------------------------

  /**
   * Turns a database constraint/trigger violation from the concurrency race window into the same
   * deterministic domain error the validator would have raised. Anything unrecognised is rethrown
   * unchanged rather than mislabelled.
   */
  private static RuntimeException translate(DataIntegrityViolationException violation) {
    if (violation instanceof DuplicateKeyException) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.DUPLICATE_RELATIONSHIP);
    }
    String message = rootMessage(violation).toLowerCase(java.util.Locale.ROOT);
    if (message.contains("is immutable")) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.IMMUTABLE_PUBLISHED_RELATIONSHIP);
    }
    if (message.contains("cycle detected")) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.SPECIALISES_CYCLE_NOT_ALLOWED);
    }
    if (message.contains("may reference only published")) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.PUBLISHED_ENDPOINT_REQUIRED);
    }
    if (message.contains("is not a curriculum prerequisite")) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE);
    }
    if (message.contains("cannot resolve the owning skill")) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.MISCONCEPTION_OWNING_SKILL_UNRESOLVABLE);
    }
    if (message.contains("misconception_relationship_not_self")
        || message.contains("misconception_relationship_canonical_order")) {
      return new MisconceptionGraphValidationException(
          MisconceptionRelationshipReasonCode.SELF_RELATIONSHIP_NOT_ALLOWED);
    }
    return violation;
  }

  private static String rootMessage(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current.getMessage() == null ? "" : current.getMessage();
  }
}
