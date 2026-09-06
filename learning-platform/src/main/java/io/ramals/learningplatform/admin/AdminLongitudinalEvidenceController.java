package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.assessment.LongitudinalEvidenceResponse;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M2-ADR-030 (H7): the admin equivalent of {@code LongitudinalEvidenceController} -- same domain
 * summary and single-misconception detail, for any learner by id rather than the authenticated
 * subject, additionally including each finding's exact baseline/latest confidence-snapshot ids, the
 * attempt that computed the latest one, and the complete ordered post-baseline evidence-observation
 * ids (never present in the learner-facing response). {@code Cache-Control: no-store}, same reasoning
 * as the learner-facing endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/learners/{learnerId}/diagnostics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLongitudinalEvidenceController {

  private final LongitudinalEvidenceService service;

  public AdminLongitudinalEvidenceController(LongitudinalEvidenceService service) {
    this.service = service;
  }

  @GetMapping("/longitudinal")
  ResponseEntity<LongitudinalEvidenceResponse> domainSummary(
      @PathVariable UUID learnerId, @RequestParam("domain") String domainCode) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(LongitudinalEvidenceResponse.fromAdminView(
            service.domainSummaryForLearner(learnerId, domainCode)));
  }

  @GetMapping("/misconceptions/{misconceptionId}/longitudinal")
  ResponseEntity<LongitudinalEvidenceResponse> misconceptionDetail(
      @PathVariable UUID learnerId, @PathVariable String misconceptionId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(LongitudinalEvidenceResponse.fromAdminView(
            service.misconceptionDetailForLearner(learnerId, misconceptionId)));
  }
}
