package io.ramals.learningplatform.assessment;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceView;
import io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus;
import io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
import io.ramals.learningplatform.assessment.DiagnosticReport.ObjectiveContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.ReportMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M2-ADR-029 (H6): HTTP-contract coverage for the four Granular Diagnostic Report endpoints --
 * authorization (learner-only vs admin-only), {@code Cache-Control: no-store} on every response, and
 * that the learner-facing JSON never carries {@code adminProvenance} while the admin-facing JSON
 * does. Mocks {@link DiagnosticReportService} entirely, the same H2-backed,
 * service-mocked pattern {@code DiagnosticSubmissionApiContractTests} already established, so this
 * never touches a real database.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:diagnostic-report;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class DiagnosticReportApiContractTests {

  private static final UUID ATTEMPT = UUID.fromString("01900000-0000-7000-8000-0000000004f1");
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000a1");

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  DiagnosticReportService service;

  private static DiagnosticReport sampleReport() {
    UUID misconceptionId = UUID.randomUUID();
    MisconceptionFinding finding = new MisconceptionFinding(
        misconceptionId, "acks=all guarantees durability", "description",
        MisconceptionTargetType.LEARNING_OBJECTIVE, misconceptionId,
        new ObjectiveContext(UUID.randomUUID(), "ACKS_DURABILITY_TRADEOFFS", "objective description"),
        null, null,
        new EvidenceSummary(4, 1, 2),
        ConfidenceState.ASSESSED,
        new ConfidenceView(DiagnosticConfidenceBand.HIGH, DiagnosticConfidenceCalculatorV1.POLICY_VERSION,
            Instant.now()),
        UUID.randomUUID(), ATTEMPT, List.of(UUID.randomUUID(), UUID.randomUUID()));
    return new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, LEARNER_ID, "KAFKA", null, Instant.now(),
        DiagnosticDataStatus.HAS_EVIDENCE, List.of(finding), List.of());
  }

  @Test
  void learnerCanReadTheirOwnCurrentDomainReport() throws Exception {
    when(service.currentDomainReport("user-1", "KAFKA")).thenReturn(sampleReport());

    mockMvc.perform(get("/api/v1/me/diagnostics/report").param("domain", "KAFKA")
            .with(jwt().jwt(token -> token.subject("user-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.reportMode").value("CURRENT_DOMAIN"))
        .andExpect(jsonPath("$.misconceptionFindings[0].adminProvenance").doesNotExist());
  }

  @Test
  void learnerCanReadTheirOwnAttemptReport() throws Exception {
    when(service.attemptReport("user-1", ATTEMPT.toString())).thenReturn(sampleReport());

    mockMvc.perform(get("/api/v1/me/diagnostics/attempts/" + ATTEMPT + "/report")
            .with(jwt().jwt(token -> token.subject("user-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void unauthenticatedLearnerRequestsAreRejected() throws Exception {
    mockMvc.perform(get("/api/v1/me/diagnostics/report").param("domain", "KAFKA"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aLearnerCannotCallTheAdminEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/admin/learners/" + LEARNER_ID + "/diagnostics/report")
            .param("domain", "KAFKA")
            .with(jwt().jwt(token -> token.subject("user-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_LEARNER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanReadAnyLearnersCurrentDomainReportWithExactProvenance() throws Exception {
    when(service.currentDomainReportForLearner(LEARNER_ID, "KAFKA")).thenReturn(sampleReport());

    mockMvc.perform(get("/api/v1/admin/learners/" + LEARNER_ID + "/diagnostics/report")
            .param("domain", "KAFKA")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.misconceptionFindings[0].adminProvenance.confidenceSnapshotId").exists())
        .andExpect(jsonPath("$.misconceptionFindings[0].adminProvenance.evidenceObservationIds").isArray());
  }

  @Test
  void adminCanReadAnyLearnersAttemptReport() throws Exception {
    when(service.attemptReportForLearner(LEARNER_ID, ATTEMPT.toString())).thenReturn(sampleReport());

    mockMvc.perform(get("/api/v1/admin/learners/" + LEARNER_ID + "/diagnostics/attempts/" + ATTEMPT + "/report")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
