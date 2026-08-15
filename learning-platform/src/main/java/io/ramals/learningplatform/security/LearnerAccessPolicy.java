package io.ramals.learningplatform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("learnerAccessPolicy")
public class LearnerAccessPolicy {

  public boolean canRead(Authentication authentication, String learnerId) {
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
        || authentication.getAuthorities().stream()
            .noneMatch(authority -> "ROLE_LEARNER".equals(authority.getAuthority()))) {
      return false;
    }
    return learnerId.equals(jwtAuthentication.getToken().getClaimAsString("learner_id"));
  }
}
