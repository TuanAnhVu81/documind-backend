package com.documind.usage.controller;

import com.documind.usage.service.UsageService;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {
    private final UsageService usage;
    public UsageController(UsageService usage) { this.usage = usage; }

    @GetMapping
    @Operation(summary = "Xem thống kê sử dụng của tôi",
            description = "Trả số lượt hỏi, token và chi phí ước tính từ khi tạo tài khoản đến thời điểm gọi. Thống kê chỉ thuộc tài khoản đang đăng nhập; replay không tăng số lượt hỏi. Giá trị null nghĩa là chưa xác định, khác với 0.")
    public ResponseEntity<UsageService.Summary> summary(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(usage.summary(UUID.fromString(jwt.getSubject())));
    }
}
