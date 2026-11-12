package com.documind.common.error;

import org.springframework.http.HttpStatus;

/** Stable API error codes, status codes and safe default details. */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Yêu cầu không thể được xử lý."),
    INVALID_DOCUMENT_SCOPE(HttpStatus.BAD_REQUEST, "Phạm vi tài liệu không hợp lệ."),
    DOCUMENT_NOT_READY(HttpStatus.CONFLICT, "Tài liệu chưa sẵn sàng."),
    GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "Hội thoại đang có lượt trả lời."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Khóa yêu cầu đã được dùng với nội dung khác."),
    GENERATION_NOT_REPLAYABLE(HttpStatus.CONFLICT, "Lượt trả lời không thể phát lại."),
    AMBIGUOUS_REFRESH_COOKIE("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Cookie xác thực không rõ ràng."),
    PASSWORD_TOO_LONG("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte UTF-8."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    INVALID_ACCESS_TOKEN("AUTHENTICATION_REQUIRED", HttpStatus.UNAUTHORIZED, "Valid authentication is required."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "Yêu cầu CSRF không hợp lệ."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Yêu cầu không thể được xử lý."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Yêu cầu không thể được xử lý."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Yêu cầu không thể được xử lý."),
    STORAGE_CAPACITY_EXCEEDED(HttpStatus.INSUFFICIENT_STORAGE, "Dung lượng lưu trữ đã đầy."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Yêu cầu không thể được xử lý."),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email đã được đăng ký."),
    LOGIN_INVALID(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không hợp lệ."),
    REFRESH_RACE(HttpStatus.CONFLICT, "Phiên đang được làm mới. Vui lòng thử lại."),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "Phiên không còn hợp lệ. Vui lòng đăng nhập lại."),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "Phiên không còn hợp lệ. Vui lòng đăng nhập lại."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Vui lòng thử lại sau."),
    HTTP_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Yêu cầu không thể được xử lý."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Hệ thống gặp lỗi. Vui lòng cung cấp mã yêu cầu khi cần hỗ trợ.");

    private final String code;
    private final HttpStatus status;
    private final String detail;

    ErrorCode(HttpStatus status, String detail) {
        this.code = name();
        this.status = status;
        this.detail = detail;
    }

    ErrorCode(String code, HttpStatus status, String detail) {
        this.code = code;
        this.status = status;
        this.detail = detail;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
    public String detail() { return detail; }
}
