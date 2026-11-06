package com.documind.chat;

import java.util.Locale;
import java.util.regex.Pattern;

/** Fixed messages contain no document facts. Language detection is deliberately limited to Vietnamese and English. */
final class SafeResponses {
    private static final Pattern VIETNAMESE = Pattern.compile("[ăâđêôơưáàảãạắằẳẵặấầẩẫậéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ]");
    private static final Pattern ENGLISH = Pattern.compile("\\b(what|which|when|where|who|why|how|does|do|is|are|the|can|please|question)\\b");

    private SafeResponses() {}

    private static boolean english(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        return !VIETNAMESE.matcher(text).find() && ENGLISH.matcher(text).find();
    }

    static String clarification(String question) {
        return english(question) ? "Could you clarify your question and specify the relevant document?"
                : "Bạn có thể làm rõ câu hỏi và cho biết tài liệu liên quan không?";
    }

    static String noEvidence(String question) {
        return english(question) ? "I could not find enough evidence in the available documents."
                : "Tôi không tìm thấy đủ bằng chứng trong các tài liệu hiện có.";
    }

    static String invalidCitation(String question) {
        return english(question) ? "I could not provide an answer with valid citations."
                : "Tôi không thể đưa ra câu trả lời với trích dẫn hợp lệ.";
    }
}
