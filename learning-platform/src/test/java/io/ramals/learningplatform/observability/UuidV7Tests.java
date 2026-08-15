package io.ramals.learningplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Tests {

  @Test
  void generatesCanonicalUuidV7() {
    UUID generated = UuidV7.generate();

    assertThat(generated.version()).isEqualTo(7);
    assertThat(generated.variant()).isEqualTo(2);
    assertThat(UuidV7.isCanonical(generated.toString())).isTrue();
  }

  @Test
  void rejectsOtherUuidVersionsAndNonCanonicalText() {
    assertThat(UuidV7.isCanonical(UUID.randomUUID().toString())).isFalse();
    assertThat(UuidV7.isCanonical("NOT-A-UUID")).isFalse();
    assertThat(UuidV7.isCanonical(null)).isFalse();
  }
}

