package com.documind.chat;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import com.documind.common.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/conversations")
public class ChatStreamController {
    private final ChatAdmissionService admission;
    private final ChatStreamService streams;
    private final JsonMapper json;

    public ChatStreamController(ChatAdmissionService admission, ChatStreamService streams, JsonMapper json) {
        this.admission = admission; this.streams = streams; this.json = json;
    }

    @PostMapping(path = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Gửi câu hỏi và nhận câu trả lời trực tuyến",
            description = "Yêu cầu Bearer JWT và Idempotency-Key là UUID. Body gồm content (tối đa 4.000 ký tự); có thể thêm documentIds chứa từ 1 đến 20 UUID tài liệu READY, hoặc bỏ trường này để tìm trong tất cả tài liệu READY. Phản hồi là luồng SSE với các sự kiện start, token, sources, done, error và heartbeat. Token là bản tạm; dùng nội dung trong done làm kết quả cuối. Gửi lại cùng key và nội dung của lượt COMPLETE sẽ phát lại kết quả mà không tạo lượt mới. Trước khi mở luồng, lỗi được trả bằng mã HTTP; sau khi mở luồng, lỗi được phát qua sự kiện error nếu kết nối còn hoạt động.")
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Parameter(required = true, description = "UUID duy nhất cho một lần gửi câu hỏi; giữ nguyên khi gửi lại cùng yêu cầu")
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody JsonNode body,
            HttpServletRequest http, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        UUID requestKey;
        try {
            requestKey = UUID.fromString(key);
            if (!requestKey.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new AppException(ErrorCode.INVALID_REQUEST); }
        var request = ChatRequest.parse(body, json);
        UUID owner = UUID.fromString(jwt.getSubject());
        var accepted = admission.admit(owner, id, requestKey, request);
        String requestId = (String) http.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (accepted.replay() != null) {
            response.setStatus(200);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            streams.replay(owner, id, accepted.replay(), requestId, response.getOutputStream());
            return null;
        }
        var emitter = streams.open(owner, id, request, accepted, requestId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(emitter);
    }
}
