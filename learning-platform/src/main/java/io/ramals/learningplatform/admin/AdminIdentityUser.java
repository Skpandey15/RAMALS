package io.ramals.learningplatform.admin;

import java.util.Set;

public record AdminIdentityUser(
    String id,
    String username,
    String email,
    boolean enabled,
    Set<String> realmRoles) {}
