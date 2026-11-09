package com.documind.chat.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Format validation only: citation membership does not prove factual support. */
@Component
public class CitationValidator {
    private static final Pattern LABEL = Pattern.compile("\\[(S[1-9][0-9]*)\\]");
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]\\r\\n]*\\]");
    private static final Pattern SENTENCE = Pattern.compile("(?s).*?(?:[.!?]+(?:\\s*\\[S[1-9][0-9]*\\])*(?=\\s|$)|$)");
    private static final Pattern CITED_END = Pattern.compile("(?s).*\\[S[1-9][0-9]*\\](?:\\s*\\[S[1-9][0-9]*\\])*[.!?]*\\s*$");
    private static final Pattern TERMINAL_LABELS = Pattern.compile("(?:\\s*\\[S[1-9][0-9]*\\])+[.!?]*\\s*$");

    public record Validation(boolean valid, List<String> sourceIds) {}

    public Validation validate(String text, Set<String> allowed) {
        if (text == null || text.isBlank() || allowed == null || allowed.isEmpty()) return new Validation(false, List.of());
        var sourceIds = new LinkedHashSet<String>();
        var brackets = BRACKETS.matcher(text);
        while (brackets.find()) {
            var label = LABEL.matcher(brackets.group());
            if (!label.matches() || !allowed.contains(label.group(1))) return new Validation(false, List.of());
            sourceIds.add(label.group(1));
        }
        String withoutLabels = LABEL.matcher(text).replaceAll("");
        if (withoutLabels.indexOf('[') >= 0 || withoutLabels.indexOf(']') >= 0) return new Validation(false, List.of());
        if (sourceIds.isEmpty()) return new Validation(false, List.of());
        for (String line : text.split("[;；\\r\\n]+")) {
            if (line.isBlank()) continue;
            var sentences = SENTENCE.matcher(line);
            while (sentences.find()) {
                String sentence = sentences.group().strip();
                if (!sentence.isEmpty() && !CITED_END.matcher(sentence).matches()) return new Validation(false, List.of());
                if (LABEL.matcher(TERMINAL_LABELS.matcher(sentence).replaceFirst("")).find()) return new Validation(false, List.of());
            }
        }
        return new Validation(true, List.copyOf(new ArrayList<>(sourceIds)));
    }
}
