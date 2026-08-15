package io.ramals.learningplatform.learning;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner's progression over a curriculum version, scoped to the authenticated subject. State is
 * derived server-side from immutable mastery snapshots and prerequisite policy.
 */
@RestController
@RequestMapping("/api/v1/me/progression")
@PreAuthorize("hasRole('LEARNER')")
public class ProgressionController {

  private final ProgressionService service;

  public ProgressionController(ProgressionService service) {
    this.service = service;
  }

  @GetMapping("/{domainCode}/versions/{versionCode}")
  ProgressionResponse progression(
      Authentication authentication,
      @PathVariable String domainCode,
      @PathVariable String versionCode) {
    return ProgressionResponse.from(domainCode, versionCode,
        service.progression(authentication.getName(), domainCode, versionCode));
  }
}
