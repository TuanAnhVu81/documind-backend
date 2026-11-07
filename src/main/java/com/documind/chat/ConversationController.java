package com.documind.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service = service; }
    public record CreateRequest(String title) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo hội thoại mới",
            description = "Tạo hội thoại thuộc tài khoản đang đăng nhập. Có thể bỏ qua tiêu đề; hệ thống dùng tiêu đề mặc định khi không cung cấp hoặc để trống.")
    public ConversationService.ConversationDto create(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) CreateRequest body) {
        return service.create(UUID.fromString(jwt.getSubject()), body == null ? null : body.title());
    }

    @GetMapping
    @Operation(summary = "Xem danh sách hội thoại của tôi",
            description = "Trả danh sách phân trang theo thời gian cập nhật gần nhất; không bao gồm các hội thoại đã xóa.")
    public ConversationService.Page list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return service.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "Xem lịch sử tin nhắn trong hội thoại",
            description = "Trả các tin nhắn theo thứ tự ordinal tăng dần. Dùng nextBeforeOrdinal của trang trước để lấy các tin nhắn cũ hơn. Hội thoại không tồn tại hoặc không thuộc tài khoản trả 404.")
    public ConversationService.MessagePage messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Parameter(description = "Chỉ lấy tin nhắn có ordinal nhỏ hơn giá trị này") @RequestParam(required = false) Long beforeOrdinal,
            @Parameter(description = "Số tin nhắn tối đa, từ 1 đến 100; mặc định 50") @RequestParam(defaultValue = "50") int limit,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.messages(UUID.fromString(jwt.getSubject()), id, beforeOrdinal, limit);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa hội thoại",
            description = "Xóa hội thoại và lịch sử tin nhắn của tài khoản đang đăng nhập. Thống kê sử dụng được giữ lại. Trả 204 khi xóa thành công; hội thoại không tồn tại hoặc không thuộc tài khoản trả 404.")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(UUID.fromString(jwt.getSubject()), id);
    }
}
