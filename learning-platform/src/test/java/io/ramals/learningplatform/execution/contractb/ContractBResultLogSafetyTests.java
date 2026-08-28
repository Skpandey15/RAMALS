package io.ramals.learningplatform.execution.contractb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.ramals.learningplatform.execution.crypto.FakeResultEncryptionKeyProvider;
import io.ramals.learningplatform.execution.crypto.ResultEncryptionKeyUnavailableException;
import io.ramals.learningplatform.execution.crypto.ResultEnvelopeCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The caller-side obligations M2-ADR-018 left for `V037`.
 *
 * <p>Criterion 9 was satisfied for the encryption layer in `#178` by giving {@code
 * ResultEnvelopeCodec} no logger at all, and the ADR recorded that <em>"every caller that handles a
 * decrypted result inherits it."</em> {@link ContractBResultStore} is the first such caller, and it
 * cannot take the codec's route: a store that says nothing when a result is refused is not
 * operable. So the obligation is discharged here by <em>what</em> is said — identity, key id,
 * digest, byte counts — and proven rather than reviewed.
 *
 * <p>Also covers the schema fail-closed row of §10 at the level where it is enforced: validation
 * happens before encryption, so a document outside the committed contract never reaches a cipher.
 *
 * <p>No database. The store's write path is exercised against a mocked {@link JdbcTemplate}, which
 * is enough because what is under test is what the class says and refuses, not what PostgreSQL
 * stores — that is the integration test's job.
 */
class ContractBResultLogSafetyTests {

  private static final String REQUEST = "req-log-safety-0001";
  private static final String KEY_V1 = "contract-b-key-v1";

  /** Distinctive, so a partial leak is a match rather than plausible noise. */
  private static final String CANARY = "CANARY-LEARNER-DIAGNOSIS-DO-NOT-PERSIST";

  private static final String VALID = """
      {"contractVersion":"1.0","proposalId":"prop-1","requestId":"req-log-safety-0001",\
      "agentRunId":"run-1","contextId":"ctx-1","diagnoses":[{"skillCode":"ALG.LIN.01",\
      "classification":"WEAK","reason":"CANARY-LEARNER-DIAGNOSIS-DO-NOT-PERSIST",\
      "evidenceIds":["ev-1","ev-2"]}],"recommendedNextSkills":["ALG.LIN.02"],"confidence":0.72}""";

  private ContractBResultStore store;
  private ListAppender<ILoggingEvent> logs;
  private Logger rootLogger;
  private Level originalLevel;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    var keys = new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1);
    jdbc = mock(JdbcTemplate.class);
    // One row inserted, which is what a first successful store does. A mock returning the default 0
    // would silently exercise the idempotent-recovery branch instead, and the success-path
    // assertions below would be describing a path they never took.
    doReturn(1).when(jdbc).update(anyString(), any(Object[].class));
    store = new ContractBResultStore(jdbc, new ResultEnvelopeCodec(keys), new ObjectMapper());

    // ROOT at TRACE, so anything logged by anything during these calls is captured -- including by
    // Jackson, the JCE provider, or a logger someone adds to the store later.
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logs = new ListAppender<>();
    logs.start();
    rootLogger.addAppender(logs);
    originalLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logs);
    rootLogger.setLevel(originalLevel);
  }

  private String capturedLogs() {
    return logs.list.stream()
        .map(event -> event.getFormattedMessage() + " " + Arrays.toString(event.getArgumentArray())
            + " " + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
        .reduce("", (a, b) -> a + "\n" + b);
  }

  @Test
  @DisplayName("negative control: the capture would see a leak if one occurred")
  void logCaptureIsCapableOfFailing() {
    // Without this, every assertion below could pass because the appender is inert rather than
    // because the store is careful.
    LoggerFactory.getLogger(ContractBResultLogSafetyTests.class).info("leak {}", VALID);

    assertThat(capturedLogs()).contains(CANARY);
  }

  @Test
  @DisplayName("a successful store logs identity, never the result")
  void successPathLogsIdentityOnly() {
    var stored = store.store(REQUEST, "msgbatch_logsafety1", VALID);

    String captured = capturedLogs();
    assertThat(captured).doesNotContain(CANARY).doesNotContain("ALG.LIN.01");
    // What it does say is the part an operator needs.
    assertThat(captured).contains(REQUEST).contains(stored.keyId()).contains(stored.digest());
  }

  @Test
  @DisplayName("a payload outside the committed contract is refused before any cipher is built")
  void schemaValidationFailsClosedBeforeEncryption() {
    // §10's fourth row. Encrypting first and validating second would produce a ciphertext of a
    // document nobody had checked -- and the prohibition on reasoning content is enforced here.
    String withReasoning =
        "{\"contractVersion\":\"1.0\",\"thinking\":\"" + CANARY + "\",\"diagnoses\":[]}";

    assertThatThrownBy(() -> store.store(REQUEST, "msgbatch_reasoning1", withReasoning))
        .isInstanceOf(ContractBResultRejectedException.class)
        .hasMessageNotContaining(CANARY);
    assertThat(capturedLogs()).doesNotContain(CANARY);
  }

  @Test
  @DisplayName("an unparseable payload is refused without echoing it")
  void malformedJsonIsRefusedWithoutEchoingIt() {
    // Jackson's own message quotes the offending input, which on this path is the learner's
    // diagnosis. The cause is dropped for exactly that reason.
    assertThatThrownBy(() -> store.store(REQUEST, "msgbatch_broken0001", "{" + CANARY))
        .isInstanceOf(ContractBResultRejectedException.class)
        .hasMessageNotContaining(CANARY)
        .hasNoCause();
    assertThat(capturedLogs()).doesNotContain(CANARY);
  }

  @Test
  @DisplayName("a rejection's whole exception chain carries no payload")
  void theRejectionChainCarriesNoPayload() {
    // The parser's cause IS kept for a schema failure, because that class builds its messages from
    // fixed strings. Asserted rather than assumed -- the whole chain is walked.
    String invalidConfidence = VALID.replace("0.72", "42");

    Throwable thrown = catchIt(() -> store.store(REQUEST, "msgbatch_conf00001", invalidConfidence));
    assertThat(thrown).isInstanceOf(ContractBResultRejectedException.class);
    for (Throwable link = thrown; link != null; link = link.getCause()) {
      assertThat(String.valueOf(link.getMessage()))
          .as("every link in the chain must be free of payload")
          .doesNotContain(CANARY);
    }
  }

  @Test
  @DisplayName("a missing key refuses the write and logs neither the result nor the key")
  void aMissingKeyRefusesTheWrite() {
    var withoutKeys = new ContractBResultStore(jdbc,
        new ResultEnvelopeCodec(new FakeResultEncryptionKeyProvider()), new ObjectMapper());

    assertThatThrownBy(() -> withoutKeys.store(REQUEST, "msgbatch_nokey0001", VALID))
        .isInstanceOf(ResultEncryptionKeyUnavailableException.class)
        .hasMessageNotContaining(CANARY);
    assertThat(capturedLogs()).doesNotContain(CANARY);
  }

  @Test
  @DisplayName("storing a result that is already there logs the recovery, not a second write")
  void anAlreadyStoredResultIsAnIdempotentNoOp() {
    // Zero rows affected: the ON CONFLICT clause kept the existing row. This is the K8 recovery
    // path -- a replacement finishing what a dead process started -- and it must be a quiet no-op
    // rather than a failure, and must still say nothing about the content.
    doReturn(0).when(jdbc).update(anyString(), any(Object[].class));

    var stored = store.store(REQUEST, "msgbatch_idempotent", VALID);

    String captured = capturedLogs();
    assertThat(captured).contains("already stored").contains(REQUEST).contains(stored.digest());
    assertThat(captured).doesNotContain(CANARY);
  }

  @Test
  @DisplayName("no key material reaches a log on any path")
  void noKeyMaterialIsLogged() {
    var keys = new FakeResultEncryptionKeyProvider().with(KEY_V1).active(KEY_V1);
    String material = java.util.Base64.getEncoder()
        .encodeToString(keys.keyFor(KEY_V1).material().getEncoded());

    store.store(REQUEST, "msgbatch_material01", VALID);

    assertThat(capturedLogs()).doesNotContain(material);
  }

  // -- the sweep must stay off every ordinary path -------------------------------------------------

  @Test
  @DisplayName("no ordinary code path invokes the ceiling sweep")
  void theSweepIsUnreachableFromOrdinaryCode() throws Exception {
    // M2-ADR-019 §3 makes this a testable property rather than a convention: the platform runtime
    // *can* reach the sweep function, and no controller, service, adapter or reconciliation path
    // may call it. Asserted over main sources, so adding such a call fails here rather than in
    // review.
    Path main = Path.of("src", "main", "java");
    try (Stream<Path> sources = Files.walk(main)) {
      List<Path> callers = sources
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.endsWith("ContractBResultPurge.java"))
          .filter(path -> {
            try {
              String text = Files.readString(path, StandardCharsets.UTF_8);
              return text.contains("ContractBResultPurge")
                  || text.contains("purge_expired_ai_execution_results");
            } catch (Exception unreadable) {
              return false;
            }
          })
          .toList();

      assertThat(callers)
          .as("the ceiling sweep is operator-invoked; an ordinary caller erases the boundary "
              + "between a targeted adoption delete and an arbitrary bulk one")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("the sweep's bounds live in the database, not in a second copy in Java")
  void theSweepDoesNotReimplementItsOwnBounds() throws Exception {
    // A Java-side window check would be a second copy of the policy -- and the copy an operator
    // invoking psql directly would bypass. The class forwards; the function refuses.
    String source = Files.readString(
        Path.of("src", "main", "java", "io", "ramals", "learningplatform", "execution",
            "contractb", "ContractBResultPurge.java"), StandardCharsets.UTF_8);

    assertThat(source)
        .as("bounds belong in core.purge_expired_ai_execution_results")
        .doesNotContain("retentionDays < 1")
        .doesNotContain("retentionDays > ");
  }

  private static Throwable catchIt(Runnable operation) {
    try {
      operation.run();
      return null;
    } catch (RuntimeException thrown) {
      return thrown;
    }
  }
}
