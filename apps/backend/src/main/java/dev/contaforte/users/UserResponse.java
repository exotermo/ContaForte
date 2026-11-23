package dev.contaforte.users;

import java.time.Instant;
import java.util.UUID;

/** Visão pública do usuário: nunca inclui o hash da senha. */
public record UserResponse(UUID id, String email, Role role, boolean enabled, Instant createdAt) {

    static UserResponse of(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getRole(), u.isEnabled(), u.getCreatedAt());
    }
}
