package dev.contaforte.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record UpdateMeRequest(
            @Email @Size(max = 320) String email,
            @Size(max = 128) String currentPassword,
            @Size(min = 12, max = 128) String newPassword) {}

    private final UserService service;

    UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.of(service.get(UUID.fromString(jwt.getSubject())));
    }

    @PatchMapping("/me")
    UserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateMeRequest body) {
        return UserResponse.of(service.update(
                UUID.fromString(jwt.getSubject()), body.email(), body.currentPassword(), body.newPassword()));
    }
}
