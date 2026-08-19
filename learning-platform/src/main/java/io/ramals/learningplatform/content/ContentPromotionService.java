package io.ramals.learningplatform.content;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.admin.AdminActivityRepository;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way content becomes {@link TrustState#VERIFIED_CONTENT}.
 *
 * <p>M1-ADR-006's approval requirement, enforced in three independent places so that removing any
 * one of them is not enough:
 *
 * <ol>
 *   <li>{@code @PreAuthorize} — the caller must hold a content-author or administrator role;
 *   <li>the reviewer's subject is written onto the row, and a database constraint refuses
 *       {@code VERIFIED_CONTENT} without one;
 *   <li>the promotion is recorded in the append-only admin audit, with the item version.
 * </ol>
 *
 * <p>The second is the one that matters most. Authorization can be bypassed by a future internal
 * caller that does not go through the web layer; the constraint cannot, because there is no way to
 * write the verified state without naming a human.
 *
 * <p>This class <em>is</em> {@code @Transactional}, and that is correct — unlike the AI call path,
 * this does no network I/O. Promotion writes a row and an audit record, which belong in one
 * transaction: content promoted without an audit entry would be a promotion nobody can trace.
 */
@Service
public class ContentPromotionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContentPromotionService.class);

  private static final String PROMOTION_METRIC = "ramals.content.promotion";

  private final ContentTrustRepository repository;
  private final AdminActivityRepository auditRepository;
  private final MeterRegistry meterRegistry;

  public ContentPromotionService(
      ContentTrustRepository repository,
      AdminActivityRepository auditRepository,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.auditRepository = auditRepository;
    this.meterRegistry = meterRegistry;
  }

  /** Raised when content cannot be promoted from the state it is in. */
  public static class PromotionRefusedException extends RuntimeException {
    public PromotionRefusedException(String message) {
      super(message);
    }
  }

  /**
   * Promotes content to {@code VERIFIED_CONTENT} on a reviewer's authority.
   *
   * @param reviewerSubject the authenticated human approving this. Never a service account: the
   *     point of the approval is that a person who understands the objective looked at the item.
   */
  @PreAuthorize("hasAnyRole('CONTENT_AUTHOR', 'ADMIN')")
  @Transactional
  public void promote(UUID itemVersionId, String reviewerSubject) {
    if (reviewerSubject == null || reviewerSubject.isBlank()) {
      throw new PromotionRefusedException("a promotion must name the reviewer");
    }

    TrustState current = repository.trustStateOf(itemVersionId)
        .orElseThrow(() -> new PromotionRefusedException("no such content"));

    if (current == TrustState.REJECTED) {
      // Rejected content is not promoted, it is regenerated or corrected. Allowing the transition
      // would make a rejection something a second click could undo, which is not what a rejection
      // means.
      recordRefusal(itemVersionId, reviewerSubject, "content was rejected");
      throw new PromotionRefusedException("rejected content cannot be promoted");
    }

    if (current == TrustState.UNVERIFIED) {
      // M1-T12 owns the only path that can turn an AI candidate into authoritative content. The
      // legacy item-level method has no immutable proposal revision, expiry, revalidation context,
      // or approval request to bind, so allowing it would be a direct approval bypass.
      recordRefusal(itemVersionId, reviewerSubject, "durable approval request is required");
      throw new PromotionRefusedException("durable approval request is required");
    }

    if (current == TrustState.VERIFIED_CONTENT) {
      // Already approved. Idempotent rather than an error, but not re-recorded: a second audit entry
      // would suggest a second review happened.
      return;
    }

    repository.promote(itemVersionId, reviewerSubject);

    auditRepository.append(
        reviewerSubject,
        "PROMOTE_CONTENT",
        "ASSESSMENT_ITEM_VERSION",
        itemVersionId,
        "SUCCESS",
        "trust state UNVERIFIED -> VERIFIED_CONTENT",
        CorrelationContext.currentInteractionId(),
        CorrelationContext.currentTraceId());

    meterRegistry.counter(PROMOTION_METRIC, "outcome", "promoted").increment();
    LOGGER.atInfo()
        .addKeyValue("operation", "content.promote")
        .addKeyValue("itemVersionId", itemVersionId)
        .addKeyValue("reviewer", reviewerSubject)
        .log("assessment content promoted to VERIFIED_CONTENT");
  }

  /** Records a pipeline rejection against the content. */
  @Transactional
  public void reject(UUID itemVersionId, ValidationStage stage, String reason) {
    repository.reject(itemVersionId, stage, reason);
    meterRegistry
        .counter(PROMOTION_METRIC, "outcome", "rejected", "stage", stage.name())
        .increment();
  }

  private void recordRefusal(UUID itemVersionId, String reviewerSubject, String detail) {
    // A refused promotion is recorded too. An audit that only holds successes cannot answer "did
    // anyone try", which is the question asked after something goes wrong.
    auditRepository.append(
        reviewerSubject,
        "PROMOTE_CONTENT",
        "ASSESSMENT_ITEM_VERSION",
        itemVersionId,
        "REJECTED",
        detail,
        CorrelationContext.currentInteractionId(),
        CorrelationContext.currentTraceId());
    meterRegistry.counter(PROMOTION_METRIC, "outcome", "refused").increment();
  }
}
