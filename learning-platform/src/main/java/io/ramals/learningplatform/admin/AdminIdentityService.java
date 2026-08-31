package io.ramals.learningplatform.admin;

import io.ramals.learningplatform.observability.CorrelationContext;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminIdentityService {

  private static final Logger LOG = LoggerFactory.getLogger(AdminIdentityService.class);
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
    rejectWorkloadTarget(userId);
    Set<String> roles = identityProvider.effectiveRealmRoles(userId);
    rejectProtectedRoles(roles);
    String detail = enabled ? "enabled" : "disabled";
    auditIntent(actorSubject, "SET_IDENTITY_ENABLED", userId, detail);
    identityProvider.setEnabled(userId, enabled);
    auditCompletion(actorSubject, "SET_IDENTITY_ENABLED", userId, detail);
    return findUser(userId);
  }

  public AdminIdentityUser addRole(String actorSubject, String userId, String rawRole) {
    requireDifferentIdentity(actorSubject, userId);
    String role = manageableRole(rawRole);
    rejectWorkloadTarget(userId);
    Set<String> roles = identityProvider.effectiveRealmRoles(userId);
    rejectProtectedRoles(roles);
    auditIntent(actorSubject, "ADD_REALM_ROLE", userId, role);
    identityProvider.addRealmRole(userId, role);
    auditCompletion(actorSubject, "ADD_REALM_ROLE", userId, role);
    return findUser(userId);
  }

  public AdminIdentityUser removeRole(String actorSubject, String userId, String rawRole) {
    requireDifferentIdentity(actorSubject, userId);
    String role = manageableRole(rawRole);
    rejectWorkloadTarget(userId);
    Set<String> roles = identityProvider.effectiveRealmRoles(userId);
    rejectProtectedRoles(roles);
    auditIntent(actorSubject, "REMOVE_REALM_ROLE", userId, role);
    identityProvider.removeRealmRole(userId, role);
    auditCompletion(actorSubject, "REMOVE_REALM_ROLE", userId, role);
    return findUser(userId);
  }

  private AdminIdentityUser findUser(String userId) {
    return identityProvider.getUser(userId);
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

  private void rejectWorkloadTarget(String userId) {
    if (identityProvider.isServiceAccount(userId)) {
      throw new IllegalArgumentException(
          "Keycloak service accounts are workload identities and cannot be managed from the staff identity surface.");
    }
  }

  private static void rejectProtectedRoles(Set<String> roles) {
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

  /**
   * Persist the intended external mutation before calling Keycloak. If this write fails, the
   * privileged mutation is not attempted. A surviving ATTEMPTED record is therefore a durable
   * reconciliation marker for a command whose final outcome could not be recorded.
   */
  private void auditIntent(String actorSubject, String action, String userId, String detail) {
    audit(actorSubject, action, userId, "ATTEMPTED", "requested: " + detail);
  }

  /**
   * Keycloak cannot participate in the database transaction. Once Keycloak has accepted the
   * mutation, failure to append the SUCCESS row must not misreport the external mutation as
   * unsuccessful. The already-durable ATTEMPTED row remains available for reconciliation.
   */
  private void auditCompletion(String actorSubject, String action, String userId, String detail) {
    try {
      audit(actorSubject, action, userId, "SUCCESS", detail);
    } catch (RuntimeException auditFailure) {
      LOG.error(
          "Identity mutation completed but completion audit failed; action={}, target={}, interactionId={}",
          action, userId, CorrelationContext.currentInteractionId(), auditFailure);
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
