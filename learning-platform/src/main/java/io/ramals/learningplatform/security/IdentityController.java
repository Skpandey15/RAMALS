package io.ramals.learningplatform.security;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {

  @GetMapping("/me")
  Map<String, String> me(Authentication authentication) {
    return Map.of("subject", authentication.getName());
  }

  @GetMapping("/learners/{learnerId}/profile")
  @PreAuthorize("@learnerAccessPolicy.canRead(authentication, #learnerId)")
  Map<String, String> learnerProfile(@PathVariable String learnerId) {
    return Map.of("learnerId", learnerId);
  }

  @GetMapping("/admin/security-check")
  @PreAuthorize("hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication)")
  Map<String, String> adminSecurityCheck() {
    return Map.of("status", "AUTHORIZED");
  }

  @GetMapping("/content/security-check")
  @PreAuthorize("hasRole('CONTENT_AUTHOR')")
  Map<String, String> contentSecurityCheck() {
    return Map.of("status", "AUTHORIZED");
  }
}
