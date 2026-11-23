package dev.contaforte.users;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas restritas a ADMIN (ver SecurityConfig: /api/v1/admin/**). */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private final UserRepository users;

    AdminUserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    List<UserResponse> list() {
        return users.findAll(PageRequest.of(0, 100, Sort.by("createdAt"))).map(UserResponse::of).getContent();
    }
}
