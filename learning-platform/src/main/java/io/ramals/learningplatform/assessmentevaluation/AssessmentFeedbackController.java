package io.ramals.learningplatform.assessmentevaluation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learner-only transport for the latest approved assessment-evaluation feedback. */
@RestController
@RequestMapping("/api/v1/me/assessment-evaluations")
@PreAuthorize("hasRole('LEARNER')")
public class AssessmentFeedbackController {

  private final AssessmentFeedbackService service;

  public AssessmentFeedbackController(AssessmentFeedbackService service) {
    this.service = service;
  }

  @GetMapping("/latest-feedback")
  ResponseEntity<AssessmentFeedback> latest(Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.latest(authentication.getName()));
  }
}
