package io.ramals.learningplatform.security;

import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("mfaAuthorization")
public class MfaAuthorization {

  public boolean hasMfa(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      return false;
    }
    Object amr = jwtAuthentication.getToken().getClaims().get("amr");
    if (amr instanceof Collection<?> methods
        && methods.stream().map(String::valueOf).anyMatch(this::isMfaMethod)) {
      return true;
    }
    Object acr = jwtAuthentication.getToken().getClaims().get("acr");
    if (acr == null) {
      return false;
    }
    try {
      return Integer.parseInt(String.valueOf(acr)) >= 2;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private boolean isMfaMethod(String method) {
    return "otp".equalsIgnoreCase(method) || "mfa".equalsIgnoreCase(method);
  }
}
