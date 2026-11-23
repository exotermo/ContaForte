package dev.contaforte.categories;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Categorias e contextos",
        description = "Categorias financeiras em 2 níveis e contextos pessoais. Os padrão do sistema são somente leitura.")
class CategoryController {

    record CreateCategoryRequest(@NotBlank @Size(max = 80) String name, UUID parentId) {}

    record NameRequest(@NotBlank @Size(max = 80) String name) {}

    private final CategoryService service;

    CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    @Operation(summary = "Árvore de categorias (padrão do sistema + as do usuário)")
    List<CategoryNode> categories(@AuthenticationPrincipal Jwt jwt) {
        return service.tree(userId(jwt));
    }

    @PostMapping("/categories")
    @Operation(summary = "Cria uma categoria ou uma subcategoria (parentId) do usuário")
    ResponseEntity<CategoryNode> createCategory(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CreateCategoryRequest body) {
        CategoryNode created = service.createCategory(userId(jwt), body.name(), body.parentId());
        return ResponseEntity.created(URI.create("/api/v1/categories/" + created.id())).body(created);
    }

    @PatchMapping("/categories/{id}")
    @Operation(summary = "Renomeia uma categoria do usuário (as padrão respondem 403)")
    CategoryNode renameCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody NameRequest body) {
        return service.renameCategory(userId(jwt), id, body.name());
    }

    @GetMapping("/contexts")
    @Operation(summary = "Contextos (padrão do sistema + os do usuário)")
    List<ContextResponse> contexts(@AuthenticationPrincipal Jwt jwt) {
        return service.listContexts(userId(jwt));
    }

    @PostMapping("/contexts")
    @Operation(summary = "Cria um contexto do usuário")
    ResponseEntity<ContextResponse> createContext(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody NameRequest body) {
        ContextResponse created = service.createContext(userId(jwt), body.name());
        return ResponseEntity.created(URI.create("/api/v1/contexts/" + created.id())).body(created);
    }

    @PatchMapping("/contexts/{id}")
    @Operation(summary = "Renomeia um contexto do usuário (os padrão respondem 403)")
    ContextResponse renameContext(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody NameRequest body) {
        return service.renameContext(userId(jwt), id, body.name());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
