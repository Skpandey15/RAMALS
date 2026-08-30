package io.ramals.learningplatform.registration;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Normalizes a submitted mobile number to E.164.
 *
 * <p><strong>Why a library rather than a regular expression.</strong> The uniqueness guarantee — one
 * verified number, one learner — is only as good as the normalization in front of it. Numbering plans
 * carry national prefixes that are dropped when dialled internationally, variable-length area codes,
 * and country-specific rules about which prefixes are mobile at all. A regular expression accepts
 * {@code 09876543210} and {@code +919876543210} as different strings, and the partial unique index
 * then happily stores both, which silently defeats the constraint it is there to enforce. libphonenumber
 * carries the actual plans and collapses those to one value.
 *
 * <p>Validity is checked, not merely parseability: a number that parses but is not a valid number for
 * the country is rejected here rather than after an SMS has been paid for.
 */
@Component
class PhoneNormalizer {

  private final PhoneNumberUtil phoneNumbers = PhoneNumberUtil.getInstance();

  /**
   * Returns the E.164 form, or rejects the number.
   *
   * <p>The rejection deliberately does not echo the submitted value. It is contact PII, and this
   * exception's message reaches the log.
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
