package com.documind.auth.dto;

public record TokenDto(String accessToken, String tokenType, long expiresIn, UserDto user) {
    @Override public String toString() { return "TokenDto[REDACTED]"; }
}
