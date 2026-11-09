package com.documind.auth.controller;

import com.documind.auth.service.AuthService;

import java.time.Clock;
import java.time.Duration;
import com.documind.auth.dto.Credentials;
import com.documind.auth.dto.TokenDto;
import com.documind.auth.dto.UserDto;
import com.documind.auth.security.AuthCookies;
import com.documind.auth.security.SignedCsrfTokens;
import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
public class AuthController {
    private final AuthService auth;
    private final AuthCookies cookies;
    private final SignedCsrfTokens csrf;
    private final Clock clock;

    public AuthController(AuthService auth, AuthCookies cookies, SignedCsrfTokens csrf, Clock clock) {
        this.auth = auth; this.cookies = cookies; this.csrf = csrf; this.clock = clock;
    }

    public record CsrfDto(String token, String headerName) {}

    @GetMapping("/csrf")
    @Operation(summary = "Lấy hoặc tái sử dụng signed CSRF token trước các POST auth")
    public ResponseEntity<CsrfDto> csrf(HttpServletRequest request) {
        String existing = AuthCookies.read(request, AuthCookies.CSRF);
        if (csrf.valid(existing)) return ResponseEntity.ok(new CsrfDto(existing, SignedCsrfTokens.HEADER));
        String token = csrf.issue();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.create(AuthCookies.CSRF, token,
                Duration.ofSeconds(SignedCsrfTokens.LIFETIME_SECONDS)).toString()).body(new CsrfDto(token, SignedCsrfTokens.HEADER));
    }

    @PostMapping("/register")
    @ApiResponses({@ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = UserDto.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST"),
            @ApiResponse(responseCode = "403", description = "CSRF_INVALID"),
            @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED; Retry-After in seconds")})
    @Operation(summary = "Tạo tài khoản; chưa cấp phiên đăng nhập", parameters = @Parameter(
            name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "Token từ GET /auth/csrf; cookie được gửi kèm"))
    public ResponseEntity<UserDto> register(@Valid @RequestBody Credentials credentials) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(credentials));
    }

    @PostMapping("/login")
    @ApiResponses({@ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TokenDto.class))),
            @ApiResponse(responseCode = "401", description = "LOGIN_INVALID"),
            @ApiResponse(responseCode = "403", description = "CSRF_INVALID"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED; Retry-After in seconds")})
    @Operation(summary = "Đăng nhập; access JWT trong JSON, refresh token trong cookie HttpOnly", parameters = @Parameter(
            name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "Token từ GET /auth/csrf; cookie được gửi kèm"))
    public ResponseEntity<TokenDto> login(@Valid @RequestBody Credentials credentials) {
        return session(auth.login(credentials));
    }

    @PostMapping("/refresh")
    @ApiResponses({@ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TokenDto.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST for ambiguous refresh cookies"),
            @ApiResponse(responseCode = "401", description = "REFRESH_INVALID or REFRESH_REUSED"),
            @ApiResponse(responseCode = "403", description = "CSRF_INVALID"),
            @ApiResponse(responseCode = "409", description = "REFRESH_RACE; Retry-After: 1; no Set-Cookie")})
    @Operation(summary = "Xoay refresh token; race trong 5 giây trả 409 và Retry-After: 1", parameters = @Parameter(
            name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "Token từ GET /auth/csrf; cookie được gửi kèm"))
    public ResponseEntity<TokenDto> refresh(HttpServletRequest request) {
        var result = auth.refresh(AuthCookies.readRefresh(request));
        return switch (result.status()) {
            case SUCCESS -> session(result.session());
            case RACE -> throw new AppException(ErrorCode.REFRESH_RACE);
            case REUSED -> throw new AppException(ErrorCode.REFRESH_REUSED);
            case INVALID -> throw new AppException(ErrorCode.REFRESH_INVALID);
        };
    }

    @PostMapping("/logout")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Refresh family revoked or absent; cookie cleared"),
            @ApiResponse(responseCode = "400", description = "INVALID_REQUEST for ambiguous refresh cookies"),
            @ApiResponse(responseCode = "403", description = "CSRF_INVALID")})
    @Operation(summary = "Thu hồi refresh family bằng cookie và CSRF, không cần access JWT", parameters = @Parameter(
            name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "Token từ GET /auth/csrf; cookie được gửi kèm"))
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        auth.logout(AuthCookies.readRefresh(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                cookies.create(AuthCookies.REFRESH, "", Duration.ZERO).toString()).build();
    }

    private ResponseEntity<TokenDto> session(AuthService.Session session) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.create(AuthCookies.REFRESH,
                session.refreshToken(), Duration.between(clock.instant(), session.expiresAt())).toString()).body(session.response());
    }
}
