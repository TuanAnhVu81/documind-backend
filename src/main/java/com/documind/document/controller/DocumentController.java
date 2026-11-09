package com.documind.document.controller;

import com.documind.document.service.DocumentService;
import com.documind.document.service.UploadRateLimiter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documents;
    private final UploadRateLimiter limiter;
    public DocumentController(DocumentService documents, UploadRateLimiter limiter) { this.documents = documents; this.limiter = limiter; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Tải tài liệu lên để xử lý",
            description = "Nhận một tệp PDF, DOCX hoặc TXT, tối đa 10 MiB. Trả 202 và trạng thái PENDING sau khi tiếp nhận; tệp chưa sẵn sàng để hỏi đáp cho đến khi xử lý xong và chuyển sang READY. Nếu vượt giới hạn upload, API trả 429 và Retry-After.")
    public DocumentService.UploadResult upload(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Tệp tài liệu cần tải lên") @RequestPart("file") MultipartFile file) throws IOException {
        UUID owner = UUID.fromString(jwt.getSubject());
        try (var reservation = limiter.reserve(owner)) {
            var result = documents.upload(owner, file);
            reservation.success();
            return result;
        }
    }

    @GetMapping
    @Operation(summary = "Xem danh sách tài liệu của tôi",
            description = "Trả danh sách phân trang, bao gồm trạng thái xử lý của từng tài liệu. Chỉ hiển thị tài liệu thuộc tài khoản đang đăng nhập.")
    public DocumentService.DocumentPage list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Số trang, bắt đầu từ 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Số tài liệu mỗi trang, từ 1 đến 100") @RequestParam(defaultValue = "20") int size) {
        return documents.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Xem chi tiết và trạng thái tài liệu",
            description = "Trả thông tin xử lý, số trang hoặc đoạn và lỗi nếu có. Tài liệu đang xóa vẫn hiển thị trạng thái DELETING cho đến khi dọn dẹp hoàn tất; tài liệu không tồn tại hoặc không thuộc tài khoản trả 404.")
    public DocumentService.DocumentDto detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return documents.detail(UUID.fromString(jwt.getSubject()), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Yêu cầu xóa tài liệu",
            description = "Trả 202 khi đã tiếp nhận yêu cầu. Tài liệu chuyển sang DELETING và được dọn dẹp nền; nội dung hội thoại cũ vẫn được giữ, còn thông tin nguồn liên quan được cập nhật khi tài liệu bị xóa.")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        documents.requestDeletion(UUID.fromString(jwt.getSubject()), id);
    }
}
