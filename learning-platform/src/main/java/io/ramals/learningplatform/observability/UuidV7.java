package io.ramals.learningplatform.observability;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {

  private static final SecureRandom RANDOM = new SecureRandom();

  private UuidV7() {
  }

  public static UUID generate() {
    long timestamp = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
    long randomA = RANDOM.nextInt(1 << 12);
    long mostSignificantBits = (timestamp << 16) | 0x7000L | randomA;
    long leastSignificantBits = RANDOM.nextLong();
    leastSignificantBits = (leastSignificantBits & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  public static boolean isCanonical(String value) {
    if (value == null || value.length() != 36 || !value.equals(value.toLowerCase())) {
      return false;
    }
    try {
      UUID uuid = UUID.fromString(value);
      return uuid.version() == 7 && uuid.variant() == 2 && uuid.toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}

