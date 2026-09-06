package io.ramals.learningplatform.assessment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M2-ADR-030 (H7): the learner-facing Longitudinal Evidence Projection -- a domain summary (every
 * misconception with a governed baseline in one domain) and a single-misconception detail (present
 * even when the learner has no eligible baseline yet). Computed on read from governed G2/G3 facts
 * only; both reflect live state on every call, so both are {@code Cache-Control: no-store}, matching
 * H6's own {@code DiagnosticReportController} precedent.
 */
@RestController
@RequestMapping("/api/v1/me/diagnostics")
@PreAuthorize("hasRole('LEARNER')")
public class LongitudinalEvidenceController {

  private final LongitudinalEvidenceService service;

  public LongitudinalEvidenceController(LongitudinalEvidenceService service) {
    this.service = service;
  }

  @GetMapping("/longitudinal")
  ResponseEntity<LongitudinalEvidenceResponse> domainSummary(
      Authentication authentication, @RequestParam("domain") String domainCode) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(LongitudinalEvidenceResponse.fromLearnerView(
            service.domainSummary(authentication.getName(), domainCode)));
  }

  @GetMapping("/misconceptions/{misconceptionId}/longitudinal")
  ResponseEntity<LongitudinalEvidenceResponse> misconceptionDetail(
      Authentication authentication, @PathVariable String misconceptionId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(LongitudinalEvidenceResponse.fromLearnerView(
            service.misconceptionDetail(authentication.getName(), misconceptionId)));
  }
}
