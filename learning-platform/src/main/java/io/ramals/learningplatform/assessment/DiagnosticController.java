package io.ramals.learningplatform.assessment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing diagnostic attempts. Creation is idempotent via the Idempotency-Key header and
 * scoped to the authenticated learner; reads are ownership-checked. Answer keys are never returned.
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
@PreAuthorize("hasRole('LEARNER')")
public class DiagnosticController {

  private final DiagnosticService service;
  private final DiagnosticSubmissionService submissionService;

  public DiagnosticController(
      DiagnosticService service, DiagnosticSubmissionService submissionService) {
    this.service = service;
    this.submissionService = submissionService;
  }

  @PostMapping("/{domainCode}/attempts")
  ResponseEntity<AttemptResponse> createAttempt(
      Authentication authentication,
      @PathVariable String domainCode,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    AttemptCreation creation =
        service.createAttempt(authentication.getName(), domainCode, idempotencyKey);
    HttpStatus status = creation.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(AttemptResponse.from(creation));
  }

  @GetMapping("/{domainCode}/attempts/{attemptId}")
  AttemptDetailResponse getAttempt(
      Authentication authentication,
      @PathVariable String domainCode,
      @PathVariable String attemptId) {
    return AttemptDetailResponse.from(
        service.getAttempt(authentication.getName(), domainCode, attemptId));
  }

  @PostMapping("/{domainCode}/attempts/{attemptId}/submit")
  SubmissionResultResponse submit(
      Authentication authentication,
      @PathVariable String domainCode,
      @PathVariable String attemptId,
      @Valid @RequestBody DiagnosticSubmissionRequest request) {
    return SubmissionResultResponse.from(
        submissionService.submit(authentication.getName(), domainCode, attemptId, request));
  }
}
