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
 * M2-ADR-029 (H6): the learner-facing Granular Diagnostic Report -- two distinct report identities
 * (never conflated): the Current Domain Diagnostic Report (complete current diagnostic view for one
 * domain) and the Attempt Diagnostic Report (findings one exact attempt produced, never "learner
 * state as of that attempt"). Both reflect live governed state on every read, so both are {@code
 * Cache-Control: no-store}, matching {@code AssessmentFeedbackController}'s own precedent for a
 * freshly-computed, learner-sensitive read.
 */
@RestController
@RequestMapping("/api/v1/me/diagnostics")
@PreAuthorize("hasRole('LEARNER')")
public class DiagnosticReportController {

  private final DiagnosticReportService service;

  public DiagnosticReportController(DiagnosticReportService service) {
    this.service = service;
  }

  @GetMapping("/report")
  ResponseEntity<DiagnosticReportResponse> currentDomainReport(
      Authentication authentication, @RequestParam("domain") String domainCode) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(DiagnosticReportResponse.fromLearnerView(
            service.currentDomainReport(authentication.getName(), domainCode)));
  }

  @GetMapping("/attempts/{attemptId}/report")
  ResponseEntity<DiagnosticReportResponse> attemptReport(
      Authentication authentication, @PathVariable String attemptId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(DiagnosticReportResponse.fromLearnerView(
            service.attemptReport(authentication.getName(), attemptId)));
  }
}
