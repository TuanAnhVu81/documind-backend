package com.documind.chat.rag;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {
    private final CitationValidator validator = new CitationValidator();

    @Test void acceptsOnlyKnownCitationsAndDeduplicatesInOrder() {
        var result = validator.validate("Điều kiện A [S2]. Điều kiện B [S1]. Nhắc lại [S2].", Set.of("S1", "S2"));
        assertThat(result.valid()).isTrue();
        assertThat(result.sourceIds()).containsExactly("S2", "S1");
    }

    @Test void rejectsMissingUnknownAndUncitedClaims() {
        assertThat(validator.validate("Không có nguồn.", Set.of("S1")).valid()).isFalse();
        assertThat(validator.validate("Nguồn lạ [S9].", Set.of("S1")).valid()).isFalse();
        assertThat(validator.validate("Có nguồn [S1]. Một nhận định khác.", Set.of("S1")).valid()).isFalse();
        assertThat(validator.validate("Sai cú pháp [Sx].", Set.of("S1")).valid()).isFalse();
        assertThat(validator.validate("Có nguồn [S1] nhưng nhãn khác [S2.", Set.of("S1")).valid()).isFalse();
        assertThat(validator.validate("A [S1]; B has 90 days.", Set.of("S1")).valid()).isFalse();
        assertThat(validator.validate("A [S1] and B has 90 days [S1].", Set.of("S1")).valid()).isFalse();
    }
}
