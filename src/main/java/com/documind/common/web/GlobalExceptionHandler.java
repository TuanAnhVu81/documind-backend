package com.documind.common.web;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ProblemDetail> application(AppException exception, HttpServletRequest request) {
        ErrorCode error = exception.errorCode();
        var response = ResponseEntity.status(error.status()).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error == ErrorCode.REFRESH_RACE) response.header(HttpHeaders.RETRY_AFTER, "1");
        if (exception instanceof com.documind.chat.ChatRateLimiter.Limited limited)
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfter()));
        if (exception instanceof com.documind.document.UploadRateLimiter.Limited limited)
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfter()));
        var problem = Problems.create(error, request);
        if (exception instanceof com.documind.chat.ChatConflict conflict) {
            if (conflict.generationId() != null) problem.setProperty("generationId", conflict.generationId());
            if (conflict.assistantMessageId() != null) problem.setProperty("assistantMessageId", conflict.assistantMessageId());
            if (conflict.generationStatus() != null) problem.setProperty("status", conflict.generationStatus());
            if (error == ErrorCode.GENERATION_IN_PROGRESS) response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(problem);
    }

    // Security filter failures are handled by SecurityProblemWriter before MVC.
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> authentication(AuthenticationException exception, HttpServletRequest request) {
        return problem(ErrorCode.AUTHENTICATION_REQUIRED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problem(ErrorCode.ACCESS_DENIED, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 400 -> ErrorCode.INVALID_REQUEST.code();
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND.code();
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED.code();
            case 413 -> ErrorCode.FILE_TOO_LARGE.code();
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE.code();
            default -> ErrorCode.HTTP_ERROR.code();
        };
        ProblemDetail problem = Problems.create(status, code, ErrorCode.INVALID_REQUEST.detail(),
                ((ServletWebRequest) request).getRequest());
        if (exception instanceof MethodArgumentNotValidException invalid) {
            Map<String, String> errors = new LinkedHashMap<>();
            invalid.getBindingResult().getFieldErrors().forEach(field -> {
                // Only simple schema field names; never return rejected values or interpolated messages.
                if (field.getField().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                    errors.putIfAbsent(field.getField(), "Giá trị không hợp lệ.");
                }
            });
            if (!errors.isEmpty()) problem.setProperty("errors", errors);
        }
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        // Exception text and stack traces may contain SQL, document text or secrets.
        log.error("Unhandled server failure type={}", exception.getClass().getName());
        return problem(ErrorCode.INTERNAL_ERROR, request);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode error, HttpServletRequest request) {
        return ResponseEntity.status(error.status()).body(Problems.create(error, request));
    }
}
