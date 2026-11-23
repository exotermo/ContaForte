package dev.contaforte.users;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rotas restritas a ADMIN (ver SecurityConfig: /api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    /** Sem campo de papel: toda pessoa criada por aqui é USER (impede escalar privilégio por esta rota). */
    record CreateUserRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    private final UserRepository users;
    private final UserService service;

    AdminUserController(UserRepository users, UserService service) {
        this.users = users;
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateUserRequest body) {
        return UserResponse.of(service.createByAdmin(UUID.fromString(jwt.getSubject()), body.email(), body.password()));
    }

    @GetMapping
    List<UserResponse> list() {
        return users.findAll(PageRequest.of(0, 100, Sort.by("createdAt"))).map(UserResponse::of).getContent();
    }
}
