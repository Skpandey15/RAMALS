package io.ramals.learningplatform.security;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Recognizes step-up / multi-factor authentication from the token. MFA is present when the
 * authentication methods reference (amr) include a second factor, or the authentication context
 * class (acr) is at least level of assurance 2 -- expressed either numerically or as a recognized
 * strong label, so the check is robust to realm LoA configuration.
 */
@Component("mfaAuthorization")
public class MfaAuthorization {

  private static final Set<String> STRONG_ACR =
      Set.of("gold", "high", "mfa", "strong", "aal2", "aal3", "loa2", "loa3");

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
    String value = String.valueOf(acr).trim();
    try {
      return Integer.parseInt(value) >= 2;
    } catch (NumberFormatException notNumeric) {
      return STRONG_ACR.contains(value.toLowerCase(Locale.ROOT));
    }
  }

  private boolean isMfaMethod(String method) {
    return "otp".equalsIgnoreCase(method) || "mfa".equalsIgnoreCase(method);
  }
}
