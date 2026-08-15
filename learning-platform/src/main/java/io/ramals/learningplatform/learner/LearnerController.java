package io.ramals.learningplatform.learner;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service learner context. Every endpoint is scoped to the caller's authenticated subject;
 * there is no addressable path for another learner, so cross-learner reads are impossible by
 * construction.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("hasRole('LEARNER')")
public class LearnerController {

  private final LearnerService service;

  public LearnerController(LearnerService service) {
    this.service = service;
  }

  @GetMapping("/profile")
  LearnerProfileResponse profile(Authentication authentication) {
    return LearnerProfileResponse.from(service.currentLearner(authentication.getName()));
  }

  @GetMapping("/goal")
  LearnerGoalResponse goal(Authentication authentication) {
    return LearnerGoalResponse.from(service.currentGoal(authentication.getName()));
  }

  @PutMapping("/goal")
  LearnerGoalResponse setGoal(
      Authentication authentication, @Valid @RequestBody LearnerGoalRequest request) {
    return LearnerGoalResponse.from(service.setGoal(authentication.getName(), request));
  }
}
