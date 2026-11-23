package dev.contaforte.accounts;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Contas", description = "Contas e carteiras do usuário (saldo derivado das transações)")
class AccountController {

    record CreateAccountRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull AccountType type,
            BigDecimal openingBalance) {}

    /** Campos ausentes não mudam. {@code type} só é aceito se igual ao atual (o tipo não pode ser alterado). */
    record UpdateAccountRequest(
            @Size(max = 80) String name,
            AccountType type,
            BigDecimal openingBalance,
            Boolean archived) {}

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Cria uma conta ou carteira")
    ResponseEntity<AccountResponse> create(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody CreateAccountRequest body) {
        AccountResponse created = service.create(userId(jwt), body.name(), body.type(), body.openingBalance());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Lista as contas do usuário com o saldo atual",
            description = "Contas arquivadas só aparecem com includeArchived=true.")
    List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(userId(jwt), includeArchived);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha uma conta")
    AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Renomeia, arquiva/reativa ou ajusta o saldo inicial (só sem transações)")
    AccountResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                           @Valid @RequestBody UpdateAccountRequest body) {
        return service.update(userId(jwt), id, body.name(), body.type(), body.openingBalance(), body.archived());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
