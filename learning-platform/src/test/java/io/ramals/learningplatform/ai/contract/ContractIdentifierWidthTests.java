package io.ramals.learningplatform.ai.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An identifier the database accepts must be an identifier the AI boundary accepts.
 *
 * <p>This exists because the two disagreed. {@code core.skill.stable_code} is {@code VARCHAR(96)}
 * and the contract capped {@code skillCode} at 64, so a skill code of 65 to 96 characters was
 * storable, legal, referenced by the deterministic engines — and rejected the moment it crossed to
 * the AI plane. Nothing failed, because every seeded Kafka code is short. It would have failed on
 * the first domain with longer names, which is the domain nobody has built yet.
 *
 * <p>The rule asserted is deliberately not "widen everything to 96". Each identifier carries the
 * width of <em>its own</em> column: a domain code is 64 because {@code learning_domain.code} is 64.
 * Widening uniformly would hide the next mismatch instead of preventing it.
 *
 * <p>Both sides are read from source — the migrations and the contract — so this cannot pass by
 * agreeing with a constant that someone updated in one place.
 */
class ContractIdentifierWidthTests {

  /** Contract field to the authoritative column that defines its width. */
  private static final Map<String, String> FIELD_TO_COLUMN = new LinkedHashMap<>(Map.of(
      "LearningContext.skillCode", "skill.stable_code",
      "DomainContext.domainCode", "learning_domain.code",
      "DomainContext.curriculumVersion", "curriculum_version.version_code",
      // The correlation identifiers the AI plane sends and this side persists (M1-ADR-011,
      // Observability HLD 9). A contract narrower than the column would make a legal identifier
      // unsendable; wider, and a legal identifier is silently rejected on insert.
      "AIProposalEnvelope.agentRunId", "ai_execution.agent_run_id",
      "AIProposalEnvelope.promptTemplateId", "ai_execution.prompt_template_id"));

  private static Path repositoryRoot() {
    return Path.of("..");
  }

  private static String contract() throws IOException {
    return Files.readString(
        repositoryRoot().resolve("contracts/ai-internal.openapi.yaml"), StandardCharsets.UTF_8);
  }

  /** Column widths taken from the migrations, which are the authority for what is storable. */
  private static Map<String, Integer> columnWidths() throws IOException {
    Path migrations = Path.of("src", "main", "resources", "db", "migration");
    Map<String, Integer> widths = new LinkedHashMap<>();

    try (Stream<Path> files = Files.list(migrations)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        Matcher tables =
            Pattern.compile("CREATE TABLE core\\.(\\w+)\\s*\\((.*?)\\n\\);", Pattern.DOTALL)
                .matcher(sql);
        while (tables.find()) {
          String table = tables.group(1);
          Matcher columns =
              Pattern.compile("^\\s*(\\w+)\\s+VARCHAR\\((\\d+)\\)", Pattern.MULTILINE)
                  .matcher(tables.group(2));
          while (columns.find()) {
            widths.put(table + "." + columns.group(1), Integer.parseInt(columns.group(2)));
          }
        }

        // Columns added later, which CREATE TABLE alone never sees. Without this a column
        // introduced by ALTER has no width check against the contract at all -- and it reads as
        // covered, because the mapping is keyed by name and the test would simply never be given
        // one to check. Later-added columns are exactly the ones whose width somebody guesses.
        Matcher altered =
            Pattern.compile("ALTER TABLE core\\.(\\w+)(.*?);", Pattern.DOTALL).matcher(sql);
        while (altered.find()) {
          String table = altered.group(1);
          Matcher added =
              Pattern.compile("ADD COLUMN\\s+(\\w+)\\s+VARCHAR\\((\\d+)\\)").matcher(altered.group(2));
          while (added.find()) {
            widths.put(table + "." + added.group(1), Integer.parseInt(added.group(2)));
          }
        }
      }
    }
    return widths;
  }

  /** The {@code maxLength} declared for a field, found by its position in the schema block. */
  private static int contractMaxLength(String contract, String schema, String field) {
    int schemaAt = contract.indexOf("\n    " + schema + ":");
    assertThat(schemaAt).as("schema %s must exist in the contract", schema).isGreaterThan(-1);

    int fieldAt = contract.indexOf("\n        " + field + ":", schemaAt);
    assertThat(fieldAt).as("field %s.%s must exist in the contract", schema, field).isGreaterThan(-1);

    Matcher maxLength =
        Pattern.compile("maxLength:\\s*(\\d+)").matcher(contract.substring(fieldAt));
    assertThat(maxLength.find())
        .as("field %s.%s must declare a maxLength", schema, field)
        .isTrue();
    return Integer.parseInt(maxLength.group(1));
  }

  @Test
  @DisplayName("each contract identifier is as wide as the column that defines it")
  void identifierWidthsMatchTheAuthoritativeSchema() throws IOException {
    String contract = contract();
    Map<String, Integer> widths = columnWidths();

    for (Map.Entry<String, String> mapping : FIELD_TO_COLUMN.entrySet()) {
      String[] parts = mapping.getKey().split("\\.");
      Integer column = widths.get(mapping.getValue());

      assertThat(column)
          .as("column %s must exist; the mapping in this test is stale", mapping.getValue())
          .isNotNull();
      assertThat(contractMaxLength(contract, parts[0], parts[1]))
          .as(
              "%s must accept everything %s can store, or a legal identifier becomes unsendable",
              mapping.getKey(), mapping.getValue())
          .isEqualTo(column);
    }
  }

  @Test
  @DisplayName("prerequisites are as wide as the skill codes they name")
  void prerequisiteItemsMatchSkillCodeWidth() throws IOException {
    String contract = contract();
    int skillCode = contractMaxLength(contract, "LearningContext", "skillCode");

    // The array items sit one indent deeper, so they are read from the prerequisites block rather
    // than by the field helper above. Widening skillCode alone left the identical defect here.
    int prerequisitesAt = contract.indexOf("\n        prerequisites:");
    assertThat(prerequisitesAt).isGreaterThan(-1);
    Matcher itemMax =
        Pattern.compile("maxLength:\\s*(\\d+)").matcher(contract.substring(prerequisitesAt));
    assertThat(itemMax.find()).isTrue();

    assertThat(Integer.parseInt(itemMax.group(1)))
        .as("prerequisites are skill stable codes and must carry the same width as skillCode")
        .isEqualTo(skillCode);
  }

  @Test
  @DisplayName("the migration scan actually finds the columns it claims to read")
  void theScanReadsRealColumns() throws IOException {
    // A width check driven by an empty map passes vacuously and proves nothing -- the failure mode
    // that let the realm guards stay green for weeks while never executing.
    Map<String, Integer> widths = columnWidths();

    assertThat(widths).hasSizeGreaterThan(20);
    assertThat(widths).containsEntry("skill.stable_code", 96);
    assertThat(widths).containsEntry("learning_domain.code", 64);
  }
}
