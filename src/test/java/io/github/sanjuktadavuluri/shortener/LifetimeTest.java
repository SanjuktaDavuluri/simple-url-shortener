package io.github.sanjuktadavuluri.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Issue #97 (spec 0004, seam 2): a Lifetime is 1 to 365 whole days and fixes a Link's Expiry. */
class LifetimeTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 365})
  void aLifetimeFromOneTo365DaysIsValid(int days) {
    assertThat(Lifetime.ofDays(days).days()).isEqualTo(days);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 366})
  void aLifetimeOutsideOneTo365DaysGivesTheValidationMessage(int days) {
    assertThatThrownBy(() -> Lifetime.ofDays(days))
        .isInstanceOf(InvalidLifetimeException.class)
        .hasMessage("expires_in_days must be a whole number of days from 1 to 365.");
  }

  @Test
  void theExpiryIsTheCreationInstantPlusTheLifetimeInWhole24HourDays() {
    Instant created = Instant.parse("2026-10-08T10:00:00Z");

    assertThat(Lifetime.ofDays(30).expiryFrom(created))
        .isEqualTo(Instant.parse("2026-11-07T10:00:00Z"));
  }
}
