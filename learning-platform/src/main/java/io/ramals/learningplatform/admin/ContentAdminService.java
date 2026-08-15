package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Controlled administration of curated content. The only mutations offered are lifecycle
 * transitions (publish a DRAFT, retire a PUBLISHED version); published content is never edited in
 * place, and the database enforces its immutability. Every operation and every rejected attempt is
 * audited with its interaction and trace ids. Audit writes are intentionally not enrolled in a
 * caller transaction, so a rejected operation still leaves an audit trail.
 */
@Service
public class ContentAdminService {

  private static final String TARGET_TYPE = "CURRICULUM_VERSION";

  private final ContentAdminRepository contentRepository;
  private final AdminActivityRepository auditRepository;

  public ContentAdminService(
      ContentAdminRepository contentRepository, AdminActivityRepository auditRepository) {
    this.contentRepository = contentRepository;
    this.auditRepository = auditRepository;
  }

  public List<CurriculumVersionSummary> listCurricula() {
    return contentRepository.listCurriculumVersions();
  }

  public CurriculumVersionSummary publishCurriculum(String actorSubject, String rawId) {
    return transition(actorSubject, rawId, "PUBLISH_CURRICULUM", "DRAFT", () -> {
      try {
        return contentRepository.publishCurriculumVersion(parseId(rawId));
      } catch (DataAccessException rejected) {
        throw new ContentPublicationException(rawId);
      }
    });
  }

  public CurriculumVersionSummary retireCurriculum(String actorSubject, String rawId) {
    return transition(actorSubject, rawId, "RETIRE_CURRICULUM", "PUBLISHED",
        () -> contentRepository.retireCurriculumVersion(parseId(rawId)));
  }

  private CurriculumVersionSummary transition(
      String actorSubject, String rawId, String action, String requiredStatus,
      Transition transition) {
    UUID id = parseId(rawId);
    CurriculumVersionSummary current = contentRepository.findCurriculumVersion(id)
        .orElseThrow(() -> new ContentVersionNotFoundException(rawId));
    if (!requiredStatus.equals(current.status())) {
      audit(actorSubject, action, id, "REJECTED", "status was " + current.status());
      throw new InvalidContentTransitionException(action, current.status());
    }
    try {
      if (!transition.run()) {
        audit(actorSubject, action, id, "REJECTED", "status changed concurrently");
        throw new InvalidContentTransitionException(action, current.status());
      }
    } catch (ContentPublicationException rejected) {
      audit(actorSubject, action, id, "REJECTED", "content integrity check failed");
      throw rejected;
    }
    audit(actorSubject, action, id, "SUCCESS", null);
    return contentRepository.findCurriculumVersion(id)
        .orElseThrow(() -> new ContentVersionNotFoundException(rawId));
  }

  private void audit(String actorSubject, String action, UUID targetId, String outcome, String detail) {
    auditRepository.append(actorSubject, action, TARGET_TYPE, targetId, outcome, detail,
        CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
  }

  private UUID parseId(String rawId) {
    try {
      return UUID.fromString(rawId);
    } catch (IllegalArgumentException notAUuid) {
      throw new ContentVersionNotFoundException(rawId);
    }
  }

  @FunctionalInterface
  private interface Transition {
    boolean run();
  }
}
