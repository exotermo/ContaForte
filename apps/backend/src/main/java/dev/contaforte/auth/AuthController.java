package dev.contaforte.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 128) String password) {}

    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    private final AuthService auth;

    AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    AuthService.Tokens login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return auth.login(body.email().trim(), body.password(), request.getRemoteAddr());
    }

    @PostMapping("/refresh")
    AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        return auth.refresh(body.refreshToken(), request.getRemoteAddr());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        auth.logout(body.refreshToken(), request.getRemoteAddr());
    }
}
