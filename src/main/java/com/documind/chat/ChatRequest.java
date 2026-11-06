package com.documind.chat;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import com.documind.retrieval.RetrievalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonical payload used for idempotency; owner and request key are never included. */
public record ChatRequest(String content, RetrievalService.Scope scope, List<UUID> documentIds, String hash) {
    public static ChatRequest parse(JsonNode body, JsonMapper json) {
        if (body == null || !body.isObject() || !body.path("content").isString()) throw new AppException(ErrorCode.INVALID_REQUEST);
        String text = body.path("content").asString().replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isBlank() || text.codePointCount(0, text.length()) > 4000) throw new AppException(ErrorCode.INVALID_REQUEST);
        List<UUID> ids = null;
        if (body.has("documentIds")) {
            var input = body.path("documentIds");
            if (!input.isArray() || input.isEmpty() || input.size() > 20) throw new AppException(ErrorCode.INVALID_DOCUMENT_SCOPE);
            ids = new ArrayList<>();
            for (var value : input) {
                if (!value.isString()) throw new AppException(ErrorCode.INVALID_DOCUMENT_SCOPE);
                try {
                    String raw = value.asString(); UUID id = UUID.fromString(raw);
                    if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
                    ids.add(id);
                } catch (IllegalArgumentException invalid) { throw new AppException(ErrorCode.INVALID_DOCUMENT_SCOPE); }
            }
            if (new HashSet<>(ids).size() != ids.size()) throw new AppException(ErrorCode.INVALID_DOCUMENT_SCOPE);
            ids.sort(Comparator.comparing(UUID::toString)); ids = List.copyOf(ids);
        }
        var scope = ids == null ? RetrievalService.Scope.ALL_READY : RetrievalService.Scope.SELECTED;
        return new ChatRequest(text, scope, ids, canonicalHash(text, scope, ids, json));
    }

    static String canonicalHash(String text, RetrievalService.Scope scope, List<UUID> ids, JsonMapper json) {
        return hashForOrder(text, scope, ids, json, List.of("content", "scope_mode", "documentIds"));
    }

    /** Phase 8 used Map.of: accept its possible field orders for this same validated request. */
    boolean matchesStoredHash(String stored, JsonMapper json) {
        if (hash.equals(stored)) return true;
        for (var order : List.of(List.of("content", "documentIds", "scope_mode"),
                List.of("scope_mode", "content", "documentIds"), List.of("scope_mode", "documentIds", "content"),
                List.of("documentIds", "content", "scope_mode"), List.of("documentIds", "scope_mode", "content"))) {
            if (hashForOrder(content, scope, documentIds, json, order).equals(stored)) return true;
        }
        return false;
    }

    static String hashForOrder(String text, RetrievalService.Scope scope, List<UUID> ids, JsonMapper json, List<String> order) {
        var values = java.util.Map.<String, Object>of("content", text, "scope_mode", scope.name(),
                "documentIds", ids == null ? List.of() : ids.stream().sorted(Comparator.comparing(UUID::toString)).toList());
        var canonical = new LinkedHashMap<String, Object>();
        for (String key : order) canonical.put(key, values.get(key));
        try {
            var bytes = json.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
