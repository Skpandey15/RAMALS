package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V048 gives {@code assessment_item_version} the logical identity it never had, backfills it for
 * the five v1 items, and makes it mandatory before a version may publish.
 */
class AssessmentItemLineageMigrationContractTests {

  @Test
  void lineageTableResolvesVersionToLogicalIdentity() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.assessment_item_lineage")
        .contains("item_version_id UUID PRIMARY KEY")
        .contains("REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT")
        .contains("logical_item_id UUID NOT NULL");
  }

  @Test
  void theFiveV1ItemsAreBackfilledEachWithItsOwnIdentity() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id) VALUES")
        // Every v1 item version, mapped to a distinct logical id.
        .contains("'01900000-0000-7000-8000-000000000411', '01900000-0000-7000-8000-000000000501'")
        .contains("'01900000-0000-7000-8000-000000000412', '01900000-0000-7000-8000-000000000502'")
        .contains("'01900000-0000-7000-8000-000000000413', '01900000-0000-7000-8000-000000000503'")
        .contains("'01900000-0000-7000-8000-000000000414', '01900000-0000-7000-8000-000000000504'")
        .contains("'01900000-0000-7000-8000-000000000415', '01900000-0000-7000-8000-000000000505'");
  }

  @Test
  void publicationRefusesAnItemWithNoLogicalIdentity() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE OR REPLACE FUNCTION core.validate_assessment_publication()")
        .contains("LEFT JOIN core.assessment_item_lineage lin ON lin.item_version_id = iv.id")
        .contains("no logical identity in assessment_item_lineage")
        // V017's trust-state gate is preserved verbatim inside the same function replacement,
        // not silently dropped by the rewrite.
        .contains("are not VERIFIED_CONTENT");
  }

  @Test
  void itemCodeIsDocumentedAsScopedNotAnIdentity() throws IOException {
    assertThat(migration())
        .contains("Unique within one assessment_version only")
        .contains("Not a cross-version identity");
  }

  private String migration() throws IOException {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V048__assessment_item_lineage.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
