package io.ramals.learningplatform.admin;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/identities")
@PreAuthorize("hasRole('ADMIN')")
public class AdminIdentityController {

  private final AdminIdentityService service;

  public AdminIdentityController(AdminIdentityService service) {
    this.service = service;
  }

  @GetMapping
  List<AdminIdentityUser> listUsers() {
    return service.listUsers();
  }

  @PatchMapping("/{userId}/enabled")
  @PreAuthorize("hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication)")
  AdminIdentityUser setEnabled(
      Authentication authentication,
      @PathVariable String userId,
      @RequestBody EnabledRequest request) {
    return service.setEnabled(authentication.getName(), userId, request.enabled());
  }

  @PostMapping("/{userId}/roles/{role}")
  @PreAuthorize("hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication)")
  AdminIdentityUser addRole(
      Authentication authentication,
      @PathVariable String userId,
      @PathVariable String role) {
    return service.addRole(authentication.getName(), userId, role);
  }

  @DeleteMapping("/{userId}/roles/{role}")
  @PreAuthorize("hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication)")
  AdminIdentityUser removeRole(
      Authentication authentication,
      @PathVariable String userId,
      @PathVariable String role) {
    return service.removeRole(authentication.getName(), userId, role);
  }

  public record EnabledRequest(boolean enabled) {}
}
