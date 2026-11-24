package dev.contaforte.transactions;

import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import dev.contaforte.common.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transações", description = "Lançamentos manuais. Valores em string decimal, no máximo 2 casas.")
class TransactionController {

    /** {@code source} não existe aqui: toda transação deste milestone é MANUAL e o cliente não escolhe. */
    record CreateTransactionRequest(
            @NotNull UUID accountId,
            UUID counterpartAccountId,
            @NotNull TransactionType type,
            @NotNull BigDecimal amount,
            Instant occurredAt,
            @Size(max = 200) String description,
            @Size(max = 120) String merchant,
            UUID categoryId,
            UUID contextId) {}

    /**
     * PATCH parcial. Campo ausente não muda; campo presente com {@code null} limpa (descrição, estabelecimento,
     * categoria e contexto). Por isso a presença é registrada nos setters. {@code type}, {@code accountId} e
     * {@code counterpartAccountId} só são aceitos se iguais aos atuais.
     */
    static class UpdateTransactionRequest {
        private final Set<String> present = new HashSet<>();
        private UUID accountId;
        private UUID counterpartAccountId;
        private TransactionType type;
        private BigDecimal amount;
        private Instant occurredAt;
        @Size(max = 200) private String description;
        @Size(max = 120) private String merchant;
        private UUID categoryId;
        private UUID contextId;

        boolean has(String field) { return present.contains(field); }

        UUID accountId() { return accountId; }
        UUID counterpartAccountId() { return counterpartAccountId; }
        TransactionType type() { return type; }
        BigDecimal amount() { return amount; }
        Instant occurredAt() { return occurredAt; }
        String description() { return description; }
        String merchant() { return merchant; }
        UUID categoryId() { return categoryId; }
        UUID contextId() { return contextId; }

        public void setAccountId(UUID v) { accountId = v; }
        public void setCounterpartAccountId(UUID v) { counterpartAccountId = v; }
        public void setType(TransactionType v) { type = v; }
        public void setAmount(BigDecimal v) { amount = v; }
        public void setOccurredAt(Instant v) { occurredAt = v; }
        @JsonSetter("description") public void setDescription(String v) { description = v; present.add("description"); }
        @JsonSetter("merchant") public void setMerchant(String v) { merchant = v; present.add("merchant"); }
        @JsonSetter("categoryId") public void setCategoryId(UUID v) { categoryId = v; present.add("categoryId"); }
        @JsonSetter("contextId") public void setContextId(UUID v) { contextId = v; present.add("contextId"); }
    }

    /** Filtros da listagem. {@code from}/{@code to} são dias (inclusivos) no fuso America/Sao_Paulo. */
    record Filter(LocalDate from, LocalDate to, UUID accountId, TransactionType type, UUID categoryId,
                  UUID contextId, String q, UUID importId) {}

    private final TransactionService service;

    TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Registra uma transação (INCOME, EXPENSE, TRANSFER, INVESTMENT ou REFUND)",
            description = "TRANSFER e INVESTMENT exigem counterpartAccountId; INVESTMENT exige conta de investimentos. "
                    + "Valor positivo com no máximo 2 casas (422 caso contrário).")
    ResponseEntity<TransactionResponse> create(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody CreateTransactionRequest body) {
        TransactionResponse created = service.create(userId(jwt), body);
        return ResponseEntity.created(URI.create("/api/v1/transactions/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Lista transações com filtros e paginação",
            description = "Ordem fixa: mais recentes primeiro. accountId casa origem ou destino; categoryId de uma "
                    + "categoria-pai inclui as subcategorias; q busca na descrição e no estabelecimento; importId lista só as de uma importação.")
    PageResponse<TransactionResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID contextId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID importId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId(jwt), new Filter(from, to, accountId, type, categoryId, contextId, q, importId), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha uma transação")
    TransactionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edita valor, data, descrição, estabelecimento, categoria e contexto",
            description = "Campo presente com null limpa descrição, estabelecimento, categoria e contexto.")
    TransactionResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                               @Valid @RequestBody UpdateTransactionRequest body) {
        return service.update(userId(jwt), id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Exclui logicamente: some de listas e saldos, a linha permanece no banco")
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(userId(jwt), id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
