package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminIdentityService {

  private static final Set<String> MANAGEABLE_HUMAN_ROLES =
      Set.of("INSTRUCTOR", "CONTENT_AUTHOR");

  private final AdminIdentityProviderClient identityProvider;
  private final AdminActivityRepository auditRepository;

  public AdminIdentityService(
      AdminIdentityProviderClient identityProvider,
      AdminActivityRepository auditRepository) {
    this.identityProvider = identityProvider;
    this.auditRepository = auditRepository;
  }

  public List<AdminIdentityUser> listUsers() {
    return identityProvider.listUsers();
  }

  public AdminIdentityUser setEnabled(String actorSubject, String userId, boolean enabled) {
    requireDifferentIdentity(actorSubject, userId);
    Set<String> roles = identityProvider.effectiveRealmRoles(userId);
    rejectProtectedTarget(roles);
    identityProvider.setEnabled(userId, enabled);
    audit(actorSubject, "SET_IDENTITY_ENABLED", userId, "SUCCESS",
        enabled ? "enabled" : "disabled");
    return findUser(userId);
  }

  public AdminIdentityUser addRole(String actorSubject, String userId, String rawRole) {
    requireDifferentIdentity(actorSubject, userId);
    String role = manageableRole(rawRole);
    Set<String> roles = identityProvider.effectiveRealmRoles(userId);
    rejectProtectedTarget(roles);
    identityProvider.addRealmRole(userId, role);
    audit(actorSubject, "ADD_REALM_ROLE", userId, "SUCCESS", role);
    return findUser(userId);
  }

  public AdminIdentityUser removeRole(String actorSubject, String userId, String rawRole) {
    requireDifferentIdentity(actorSubject, userId);
    String role = manageableRole(rawRole);
    Set<String> roles = identityProvider.effectiveRealmRoles(userId);
    rejectProtectedTarget(roles);
    identityProvider.removeRealmRole(userId, role);
    audit(actorSubject, "REMOVE_REALM_ROLE", userId, "SUCCESS", role);
    return findUser(userId);
  }

  private AdminIdentityUser findUser(String userId) {
    return identityProvider.listUsers().stream()
        .filter(user -> user.id().equals(userId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Identity no longer exists."));
  }

  private static String manageableRole(String rawRole) {
    if (rawRole == null) {
      throw new IllegalArgumentException("Role is required.");
    }
    String role = rawRole.trim().toUpperCase(Locale.ROOT);
    if (!MANAGEABLE_HUMAN_ROLES.contains(role)) {
      throw new IllegalArgumentException(
          "Only INSTRUCTOR and CONTENT_AUTHOR are assignable from this administrative surface.");
    }
    return role;
  }

  private static void rejectProtectedTarget(Set<String> roles) {
    if (roles.contains("ADMIN") || roles.contains("SERVICE") || roles.contains("LEARNER")) {
      throw new IllegalArgumentException(
          "ADMIN, SERVICE, and LEARNER identities are outside staff identity administration.");
    }
  }

  private static void requireDifferentIdentity(String actorSubject, String userId) {
    if (actorSubject.equals(userId)) {
      throw new IllegalArgumentException("Administrators cannot mutate their own identity.");
    }
  }

  private void audit(
      String actorSubject, String action, String userId, String outcome, String detail) {
    UUID targetId;
    try {
      targetId = UUID.fromString(userId);
    } catch (IllegalArgumentException notUuid) {
      targetId = null;
    }
    auditRepository.append(actorSubject, action, "IDENTITY_USER", targetId, outcome, detail,
        CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
  }
}
