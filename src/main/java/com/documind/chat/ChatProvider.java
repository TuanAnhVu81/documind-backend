package com.documind.chat;

/** Provider calls are performed outside database transactions. */
public interface ChatProvider {
    record Reply(String text, Long promptTokens, Long completionTokens) {}
    Reply complete(String system, String input);
}
