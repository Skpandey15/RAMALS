package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.assessment.DiagnosticReportResponse;
import io.ramals.learningplatform.assessment.DiagnosticReportService;
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
 * M2-ADR-029 (H6): the admin equivalent of {@code DiagnosticReportController} -- same two report
 * identities, for any learner by id rather than the authenticated subject, additionally including
 * each assessed finding's exact confidence/evidence provenance ids (never present in the
 * learner-facing response). {@code Cache-Control: no-store}, same reasoning as the learner-facing
 * endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/learners/{learnerId}/diagnostics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiagnosticReportController {

  private final DiagnosticReportService service;

  public AdminDiagnosticReportController(DiagnosticReportService service) {
    this.service = service;
  }

  @GetMapping("/report")
  ResponseEntity<DiagnosticReportResponse> currentDomainReport(
      @PathVariable UUID learnerId, @RequestParam("domain") String domainCode) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(DiagnosticReportResponse.fromAdminView(
            service.currentDomainReportForLearner(learnerId, domainCode)));
  }

  @GetMapping("/attempts/{attemptId}/report")
  ResponseEntity<DiagnosticReportResponse> attemptReport(
      @PathVariable UUID learnerId, @PathVariable String attemptId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(DiagnosticReportResponse.fromAdminView(
            service.attemptReportForLearner(learnerId, attemptId)));
  }
}
