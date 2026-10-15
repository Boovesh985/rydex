package com.rydex.ride.auth;

import com.rydex.ride.user.Role;
import com.rydex.ride.user.UserView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class AuthDtos {

    private AuthDtos() {
    }

    record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +") String phone,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "(?s)^(?=.*\\p{L})(?=.*\\d).*$", message = "must contain at least one letter and one digit")
            String password,
            @NotNull Role role) {
    }

    /** The password is only bounded, never validated: old accounts may predate the current rules. */
    record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {
    }

    record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, UserView user) {
    }
}
