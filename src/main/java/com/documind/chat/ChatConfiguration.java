package com.documind.chat;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "documind.chat.enabled", havingValue = "true")
public class ChatConfiguration {
    @Bean ChatProvider chatProvider(@Value("${GEMINI_API_KEY:}") String key, JsonMapper json) {
        return new GeminiChatProvider(key, URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"), json);
    }
}
