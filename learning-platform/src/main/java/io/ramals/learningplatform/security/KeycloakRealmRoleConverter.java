package io.ramals.learningplatform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final List<String> APPROVED_ROLES =
      List.of("LEARNER", "INSTRUCTOR", "CONTENT_AUTHOR", "ADMIN", "SERVICE");
  private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (!(realmAccess instanceof Map<?, ?> realm)) {
      return authorities;
    }
    Object roles = realm.get("roles");
    if (roles instanceof Collection<?> collection) {
      collection.stream()
          .map(String::valueOf)
          .filter(APPROVED_ROLES::contains)
          .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .forEach(authorities::add);
    }
    return authorities;
  }
}
