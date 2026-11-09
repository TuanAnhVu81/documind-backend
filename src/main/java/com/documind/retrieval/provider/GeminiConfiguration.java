package com.documind.retrieval.provider;

import java.net.URI;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "documind.gemini.enabled", havingValue = "true")
public class GeminiConfiguration {
    @Bean EmbeddingModel geminiEmbeddingModel(@Value("${GEMINI_API_KEY:}") String key, JsonMapper json) {
        return new GeminiEmbeddingModel(key, URI.create("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-2:batchEmbedContents"), json);
    }
}
