package com.documind.chat;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SafeResponsesTest {
    @Test void usesFixedVietnameseAndEnglishText() {
        assertThat(SafeResponses.noEvidence("Điều kiện hoàn tiền là gì?")).contains("không tìm thấy");
        assertThat(SafeResponses.noEvidence("What is the refund policy?")).contains("could not find");
        assertThat(SafeResponses.clarification("How long?")).contains("clarify");
    }
}
