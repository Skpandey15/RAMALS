package io.ramals.learningplatform.recommendation;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner's current recommendations, scoped to the authenticated subject. Each item links to
 * its immutable decision record for support and audit.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("hasRole('LEARNER')")
public class RecommendationController {

  private final RecommendationService service;

  public RecommendationController(RecommendationService service) {
    this.service = service;
  }

  @GetMapping("/recommendations")
  RecommendationResponse recommendations(Authentication authentication) {
    return RecommendationResponse.from(service.currentRecommendations(authentication.getName()));
  }
}
