package com.documind.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.documind.common.error.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatRequestTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private ChatRequest parse(String value) { return ChatRequest.parse(json.readTree(value), json); }

    @Test void hashNormalizesLinesAndScopeOrder() {
        String a = "00000000-0000-0000-0000-000000000001", b = "00000000-0000-0000-0000-000000000002";
        var first = parse("{\"content\":\"  hello\\r\\nworld  \",\"documentIds\":[\"" + b + "\",\"" + a + "\"]}");
        var second = parse("{\"content\":\"hello\\nworld\",\"documentIds\":[\"" + a + "\",\"" + b + "\"]}");
        assertThat(first.hash()).isEqualTo(second.hash());
        assertThat(parse("{\"content\":\"hello\\nworld\"}").hash()).isNotEqualTo(first.hash());
    }

    @Test void rejectsExplicitNullEmptyAndDuplicateScope() {
        for (String ids : new String[]{"null", "[]", "[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000001\"]"})
            assertThatThrownBy(() -> parse("{\"content\":\"x\",\"documentIds\":" + ids + "}")).isInstanceOf(AppException.class);
    }

    @Test void validatesUnicodeCodePointsRatherThanUtf16Units() {
        assertThat(parse("{\"content\":\"" + "😀".repeat(4000) + "\"}").content().codePointCount(0, 8000)).isEqualTo(4000);
        assertThatThrownBy(() -> parse("{\"content\":\"" + "😀".repeat(4001) + "\"}"))
                .isInstanceOf(AppException.class);
    }

    @Test void acceptsAllLegacyMapOrdersOnlyForSameRequest() {
        var request = parse("{\"content\":\"x\",\"documentIds\":[\"00000000-0000-0000-0000-000000000001\"]}");
        var other = parse("{\"content\":\"x\",\"documentIds\":[\"00000000-0000-0000-0000-000000000002\"]}");
        for (var order : java.util.List.of(java.util.List.of("content", "scope_mode", "documentIds"),
                java.util.List.of("content", "documentIds", "scope_mode"), java.util.List.of("scope_mode", "content", "documentIds"),
                java.util.List.of("scope_mode", "documentIds", "content"), java.util.List.of("documentIds", "content", "scope_mode"),
                java.util.List.of("documentIds", "scope_mode", "content"))) {
            var legacy = ChatRequest.hashForOrder(request.content(), request.scope(), request.documentIds(), json, order);
            assertThat(request.matchesStoredHash(legacy, json)).isTrue();
            assertThat(other.matchesStoredHash(legacy, json)).isFalse();
        }
    }
}
