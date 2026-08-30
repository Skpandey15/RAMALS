package io.ramals.learningplatform.registration;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Normalizes a submitted mobile number to E.164.
 *
 * <p>A library rather than a regular expression, because the uniqueness guarantee is only as good
 * as the normalization in front of it: a regex accepts {@code 09876543210} and
 * {@code +919876543210} as different strings and the partial unique index then stores both.
 * Validity is checked, not just parseability, so an invalid number is refused before an SMS is
 * paid for.
 */
@Component
class PhoneNormalizer {

  private final PhoneNumberUtil phoneNumbers = PhoneNumberUtil.getInstance();

  /**
   * Returns the E.164 form, or rejects the number without echoing the submitted value, which is
   * contact PII and would reach the log.
   */
  String normalize(String rawNumber, String country) {
    try {
      Phonenumber.PhoneNumber parsed =
          phoneNumbers.parse(rawNumber, country.toUpperCase(Locale.ROOT));
      if (!phoneNumbers.isValidNumber(parsed)) {
        throw RegistrationException.invalidMobileNumber();
      }
      return phoneNumbers.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
    } catch (NumberParseException unparseable) {
      throw RegistrationException.invalidMobileNumber();
    }
  }
}
