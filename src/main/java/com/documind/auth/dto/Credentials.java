package com.documind.auth.dto;

import java.util.Locale;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Credentials(@NotBlank @Email @Size(max = 254) String email,
                          @NotBlank @Size(min = 8, max = 72) String password) {
    public Credentials {
        if (email != null) email = email.strip().toLowerCase(Locale.ROOT);
    }
    @Override public String toString() { return "Credentials[REDACTED]"; }
}
