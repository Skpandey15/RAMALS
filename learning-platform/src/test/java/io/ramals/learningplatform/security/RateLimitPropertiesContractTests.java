package io.ramals.learningplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The YAML defaults must agree with the Java defaults.
 *
 * <p>They did not, and nothing noticed for a release. {@code RateLimitProperties} defines the
 * pre-authentication IP tier as 600/300 and says in its own Javadoc that a whole office or school
 * behind one NAT must not trip it. {@code application.yml} bound that same tier to 120/60 — the
 * numbers belonging to the per-subject tier — and did not bind the subject tier at all.
 *
 * <p>A value in the YAML always wins over a Java field default, so the two-tier split existed in the
 * code and not in any deployed system: the shared IP bucket was five times tighter than designed,
 * and the per-learner tier was unreachable from configuration. R1 Run A measured the result — 2,153
 * of 12,417 requests refused with HTTP 429 at a sustained 60 rps from one address.
 *
 * <p>What makes this worth a gate rather than a comment is that neither file was wrong on its own.
 * Each was internally consistent and individually reviewable, and the defect lived only in the
 * relationship between them. So the relationship is what is asserted here.
 */
class RateLimitPropertiesContractTests {

  private static final Path APPLICATION_YML = Path.of("src", "main", "resources", "application.yml");

  /**
   * Every rate-limit placeholder in the YAML, as {@code property path -> default}.
   *
   * <p>Parsed rather than hardcoded. A copy of the expected YAML inside the test would pass by
   * agreeing with itself, which is the failure mode this whole test exists to catch one level up.
   */
  private static Map<String, String> yamlDefaults() throws IOException {
    String yaml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8).replace("\r\n", "\n");

    int start = yaml.indexOf("    rate-limit:");
    assertThat(start).as("application.yml must configure ramals.security.rate-limit").isGreaterThan(-1);

    // The block ends at the next line indented four spaces or fewer that is not part of it.
    String remainder = yaml.substring(start + "    rate-limit:".length());
    int end = remainder.length();
    int offset = 0;
    for (String line : remainder.split("\n", -1)) {
      if (!line.isBlank() && !line.startsWith("      ") && !line.startsWith("    #")) {
        end = offset;
        break;
      }
      offset += line.length() + 1;
    }
    String block = remainder.substring(0, Math.min(end, remainder.length()));

    // Tracks nesting so `capacity` under `subject:` is recorded as `subject.capacity` rather than
    // silently colliding with the top-level key of the same name — which is exactly the confusion
    // that produced the defect.
    Map<String, String> defaults = new LinkedHashMap<>();
    Pattern placeholder = Pattern.compile("^(\\s*)([a-z-]+):\\s*\\$\\{([A-Z_]+):([^}]*)}\\s*$");
    Pattern nesting = Pattern.compile("^(\\s*)([a-z-]+):\\s*$");
    String prefix = "";

    for (String line : block.split("\n")) {
      if (line.isBlank() || line.trim().startsWith("#")) {
        continue;
      }
      Matcher value = placeholder.matcher(line);
      if (value.matches()) {
        String indent = value.group(1);
        String key = (indent.length() > 6 ? prefix : "") + value.group(2);
        defaults.put(key, value.group(4));
        continue;
      }
      Matcher nested = nesting.matcher(line);
      if (nested.matches()) {
        prefix = nested.group(2) + ".";
      }
    }
    return defaults;
  }

  @Test
  @DisplayName("the IP tier's YAML default matches its Java default")
  void ipTierDefaultsAgree() throws IOException {
    RateLimitProperties javaDefaults = new RateLimitProperties();
    Map<String, String> yaml = yamlDefaults();

    assertThat(yaml)
        .as("the pre-authentication tier must be bound so it can be tuned per environment")
        .containsKeys("capacity", "refill-per-second");

    assertThat(Integer.parseInt(yaml.get("capacity")))
        .as(
            "application.yml binds the pre-authentication IP capacity to %s, but "
                + "RateLimitProperties defaults it to %d. A YAML value always wins, so this is the "
                + "figure every deployment gets. This tier is shared by everyone behind one NAT.",
            yaml.get("capacity"), javaDefaults.getCapacity())
        .isEqualTo(javaDefaults.getCapacity());

    assertThat(Double.parseDouble(yaml.get("refill-per-second")))
        .as("the IP tier's refill rate must match RateLimitProperties")
        .isEqualTo(javaDefaults.getRefillPerSecond());

    // The key ceiling is bound for the same reason as the other two: unbound, it is reachable only
    // by editing Java, and it is the one value an operator needs during a key-rotation flood.
    assertThat(yaml)
        .as("the IP tier's key ceiling must be bound so it can be raised without a rebuild")
        .containsKey("max-buckets");
    assertThat(Integer.parseInt(yaml.get("max-buckets")))
        .as("the IP tier's key ceiling must match RateLimitProperties")
        .isEqualTo(javaDefaults.getMaxBuckets());
  }

  @Test
  @DisplayName("the subject tier's YAML default matches its Java default")
  void subjectTierDefaultsAgree() throws IOException {
    RateLimitProperties.Subject javaDefaults = new RateLimitProperties().getSubject();
    Map<String, String> yaml = yamlDefaults();

    assertThat(yaml)
        .as("the per-subject tier must be bound; unbound, it is reachable only by editing Java")
        .containsKeys("subject.capacity", "subject.refill-per-second");

    assertThat(Integer.parseInt(yaml.get("subject.capacity")))
        .as("the per-learner capacity must match RateLimitProperties")
        .isEqualTo(javaDefaults.getCapacity());

    assertThat(Double.parseDouble(yaml.get("subject.refill-per-second")))
        .as("the per-learner refill rate must match RateLimitProperties")
        .isEqualTo(javaDefaults.getRefillPerSecond());

    assertThat(yaml)
        .as("the subject tier's key ceiling must be bound")
        .containsKey("subject.max-buckets");
    assertThat(Integer.parseInt(yaml.get("subject.max-buckets")))
        .as("the per-learner key ceiling must match RateLimitProperties")
        .isEqualTo(javaDefaults.getMaxBuckets());
  }

  @Test
  @DisplayName("every tier bounds the number of buckets it will retain")
  void everyTierBoundsItsBucketTable() {
    RateLimitProperties properties = new RateLimitProperties();

    // The unbounded map was the finding (TD-M2-SEC-01). Asserted as an invariant over both tiers so
    // that a tier added later cannot reintroduce it by simply omitting the ceiling.
    assertThat(properties.getMaxBuckets())
        .as("the IP tier must bound its bucket table; unbounded, address rotation grows the heap")
        .isPositive();
    assertThat(properties.getSubject().getMaxBuckets())
        .as("the subject tier must bound its bucket table")
        .isPositive();

    assertThat(properties.getMaxBuckets())
        .as(
            "the IP tier's key space is the reachable internet and the subject tier's is the set of "
                + "authenticated learners, so the IP ceiling must be the larger of the two")
        .isGreaterThan(properties.getSubject().getMaxBuckets());
  }

  @Test
  @DisplayName("the two tiers are not bound to the same numbers")
  void theTiersAreDistinct() throws IOException {
    Map<String, String> yaml = yamlDefaults();

    // The defect's signature. Both tiers carrying identical values means the split has been undone
    // in configuration whatever the code says, and the generous shared-IP allowance is gone.
    boolean identical =
        yaml.get("capacity").equals(yaml.get("subject.capacity"))
            && yaml.get("refill-per-second").equals(yaml.get("subject.refill-per-second"));

    assertThat(identical)
        .as(
            "the IP tier (%s/%s) and the subject tier (%s/%s) are bound to identical values, which "
                + "collapses the two-tier design: everyone behind one NAT shares what is meant to "
                + "be one learner's allowance",
            yaml.get("capacity"),
            yaml.get("refill-per-second"),
            yaml.get("subject.capacity"),
            yaml.get("subject.refill-per-second"))
        .isFalse();
  }

  @Test
  @DisplayName("the IP tier is the more generous of the two")
  void theIpTierIsTheGenerousOne() throws IOException {
    Map<String, String> yaml = yamlDefaults();

    // Stated as an invariant rather than as two numbers, so it survives both being retuned. A
    // shared bucket smaller than a single user's is incoherent in either direction.
    assertThat(Integer.parseInt(yaml.get("capacity")))
        .as("the shared IP bucket must be larger than one learner's, not smaller")
        .isGreaterThan(Integer.parseInt(yaml.get("subject.capacity")));

    assertThat(Double.parseDouble(yaml.get("refill-per-second")))
        .as("the shared IP refill must exceed one learner's")
        .isGreaterThan(Double.parseDouble(yaml.get("subject.refill-per-second")));
  }

  @Test
  @DisplayName("each tier's environment variable names the tier it configures")
  void environmentVariablesNameTheirTier() throws IOException {
    String yaml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8).replace("\r\n", "\n");

    // compose.perf-override.yml sets RAMALS_RATE_LIMIT_SUBJECT_* and, before the subject tier was
    // bound, those variables matched no property and were read by nothing. The override looked like
    // it relaxed both tiers and relaxed one.
    assertThat(yaml)
        .as("the subject tier must be settable by an environment variable that names it")
        .contains("${RAMALS_RATE_LIMIT_SUBJECT_CAPACITY:")
        .contains("${RAMALS_RATE_LIMIT_SUBJECT_REFILL_PER_SECOND:");

    // The IP tier's variables must NOT carry SUBJECT in the name, or one override would move both.
    Matcher ipCapacity =
        Pattern.compile("\\n\\s{6}capacity:\\s*\\$\\{([A-Z_]+):").matcher(yaml);
    assertThat(ipCapacity.find()).as("the IP tier's capacity must be bound to a variable").isTrue();
    assertThat(ipCapacity.group(1))
        .as("the IP tier must not be configured by a SUBJECT-named variable")
        .doesNotContain("SUBJECT");
  }

  @Test
  @DisplayName("every rate-limit variable the perf override sets binds to a real property")
  void thePerformanceOverrideSetsOnlyVariablesThatBind() throws IOException {
    Path override = Path.of("..", "performance", "compose.perf-override.yml");
    assertThat(override).as("the documented capacity override").exists();

    String yaml = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
    String overrideText = Files.readString(override, StandardCharsets.UTF_8);

    // A variable set in the override that application.yml never reads is inert: the run looks
    // relaxed and is not. Both SUBJECT_ variables were in exactly that state, which is why R1 Run B
    // relaxed the IP ceiling alone while appearing to lift both tiers.
    Matcher declared =
        Pattern.compile("^\\s+(RAMALS_RATE_LIMIT[A-Z_]*):", Pattern.MULTILINE).matcher(overrideText);

    int checked = 0;
    while (declared.find()) {
      String variable = declared.group(1);
      checked++;
      assertThat(yaml)
          .as(
              "compose.perf-override.yml sets %s, but application.yml never reads it, so it "
                  + "configures nothing and the override silently does less than it claims",
              variable)
          .contains("${" + variable + ":");
    }

    assertThat(checked)
        .as("the override should declare rate-limit variables; finding none means this parsed nothing")
        .isGreaterThan(0);
  }

  @Test
  @DisplayName("rate limiting is not disabled by default")
  void rateLimitingIsOnByDefault() throws IOException {
    Map<String, String> yaml = yamlDefaults();

    assertThat(yaml.get("enabled"))
        .as("rate limiting must default to on; a tuning change must never become a disable")
        .isEqualTo("true");
    assertThat(new RateLimitProperties().isEnabled())
        .as("the Java default must agree")
        .isTrue();
  }
}
