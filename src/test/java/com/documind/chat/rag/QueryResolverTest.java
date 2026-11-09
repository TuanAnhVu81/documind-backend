package com.documind.chat.rag;

import com.documind.chat.provider.ChatProvider;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QueryResolverTest {
    @Test void excludesDeletedAndFailedHistoryAndAllowsClarification() {
        var resolver = new QueryResolver(JsonMapper.builder().build());
        var history = List.of(new QueryResolver.Turn("cũ", "bí mật", "COMPLETE", "ANSWER", false),
                new QueryResolver.Turn("hỏng", "không dùng", "ERROR", "PROVIDER_ERROR", true),
                new QueryResolver.Turn("Hợp đồng A?", "Hoàn tiền [S1]", "COMPLETE", "ANSWER", true));
        ChatProvider model = (system, input) -> {
            assertThat(input).contains("Hợp đồng A").doesNotContain("bí mật", "không dùng");
            return new ChatProvider.Reply("{\"question\":\"Điều kiện thứ hai của hợp đồng A?\",\"clarification\":null}", 20L, 10L);
        };
        var result = resolver.resolve("Điều kiện thứ hai?", history, model);
        assertThat(result.question()).isEqualTo("Điều kiện thứ hai của hợp đồng A?");
        assertThat(result.clarification()).isNull();
        assertThat(resolver.resolve("Hỏi độc lập", List.of(), null).question()).isEqualTo("Hỏi độc lập");
    }

    @Test void oversizedHistoryDoesNotSilentlyTreatFollowUpAsStandalone() {
        var resolver = new QueryResolver(JsonMapper.builder().build());
        var history = List.of(new QueryResolver.Turn("Hợp đồng nào?", "Thông tin ".repeat(500), "COMPLETE", "ANSWER", true));
        ChatProvider model = (system, input) -> { throw new AssertionError("Should ask safely without a provider call"); };
        var result = resolver.resolve("Điều kiện thứ hai?", history, model);
        assertThat(result.question()).isNull();
        assertThat(result.clarification()).doesNotContain("Bịa").contains("làm rõ");
    }

    @Test void neverPersistsFactsFromModelClarification() {
        var resolver = new QueryResolver(JsonMapper.builder().build());
        var history = List.of(new QueryResolver.Turn("Câu hỏi?", "Có [S1]", "COMPLETE", "ANSWER", true));
        ChatProvider model = (system, input) -> new ChatProvider.Reply(
                "{\"question\":null,\"clarification\":\"Tài liệu nói hoàn tiền 100%, đúng không?\"}", 1L, 1L);
        assertThat(resolver.resolve("Còn thời hạn?", history, model).clarification())
                .doesNotContain("100%", "hoàn tiền").contains("làm rõ");
    }
}
