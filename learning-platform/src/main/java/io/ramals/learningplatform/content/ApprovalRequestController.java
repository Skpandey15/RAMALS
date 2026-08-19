package io.ramals.learningplatform.content;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approval-requests")
@PreAuthorize("hasAnyRole('CONTENT_AUTHOR', 'ADMIN')")
public class ApprovalRequestController {
  private final ApprovalRequestService service;

  public ApprovalRequestController(ApprovalRequestService service) {
    this.service = service;
  }

  @PostMapping
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  ResponseEntity<ApprovalRequestResponse> create(Authentication authentication,
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateApprovalRequest request) {
    ApprovalRequest result = service.create(request.candidateId(), request.candidateRevision(),
        authentication.getName(), key);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalRequestResponse.from(result));
  }

  @GetMapping("/{id}")
  ApprovalRequestResponse get(@PathVariable UUID id) {
    return ApprovalRequestResponse.from(service.get(id));
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  ApprovalRequestResponse approve(Authentication authentication, @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key) {
    return ApprovalRequestResponse.from(service.approve(id, authentication.getName(), key));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  ApprovalRequestResponse reject(Authentication authentication, @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RejectApprovalRequest request) {
    return ApprovalRequestResponse.from(service.reject(id, authentication.getName(), key, request.reason()));
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasRole('CONTENT_AUTHOR') or (hasRole('ADMIN') and @mfaAuthorization.hasMfa(authentication))")
  ApprovalRequestResponse cancel(Authentication authentication, @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key) {
    return ApprovalRequestResponse.from(service.cancel(id, authentication.getName(), key));
  }
}
