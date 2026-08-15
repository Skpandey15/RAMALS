package io.ramals.learningplatform.mastery;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The learner's mastery map over a curriculum version, scoped to the authenticated subject. */
@RestController
@RequestMapping("/api/v1/me/mastery")
@PreAuthorize("hasRole('LEARNER')")
public class MasteryMapController {

  private final MasteryMapService service;

  public MasteryMapController(MasteryMapService service) {
    this.service = service;
  }

  @GetMapping("/{domainCode}/versions/{versionCode}")
  MasteryMapResponse masteryMap(
      Authentication authentication,
      @PathVariable String domainCode,
      @PathVariable String versionCode) {
    return MasteryMapResponse.from(domainCode, versionCode,
        service.masteryMap(authentication.getName(), domainCode, versionCode));
  }
}
