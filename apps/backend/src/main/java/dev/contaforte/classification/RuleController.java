package dev.contaforte.classification;

import com.fasterxml.jackson.annotation.JsonSetter;
import dev.contaforte.transactions.TransactionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categorization-rules")
@Tag(name = "Regras de categorização", description = "Regras do usuário: texto da descrição, normalizado, comparado por palavras inteiras.")
class RuleController {

    record CreateRuleRequest(
            @NotNull MatchType matchType,
            @NotBlank @Size(max = 200) String pattern,
            TransactionType transactionType,
            UUID categoryId,
            UUID contextId,
            @Min(0) @Max(10000) Integer priority,
            Boolean enabled) {}

    /** PATCH parcial: ausente não muda; presente com null limpa tipo, categoria e contexto. */
    static class UpdateRuleRequest {
        private final CategorizationRuleService.UpdateFields f = new CategorizationRuleService.UpdateFields();

        public void setMatchType(MatchType v) { f.match = v; }
        @Size(max = 200) public String getPattern() { return f.pattern; }
        public void setPattern(String v) { f.pattern = v; }
        @JsonSetter("transactionType") public void setTransactionType(TransactionType v) { f.type = v; f.typePresent = true; }
        @JsonSetter("categoryId") public void setCategoryId(UUID v) { f.categoryId = v; f.categoryPresent = true; }
        @JsonSetter("contextId") public void setContextId(UUID v) { f.contextId = v; f.contextPresent = true; }
        @Min(0) @Max(10000) public Integer getPriority() { return f.priority; }
        public void setPriority(Integer v) { f.priority = v; }
        public void setEnabled(Boolean v) { f.enabled = v; }
    }

    record TestRequest(@Size(max = 200) String description, @Size(max = 120) String merchant, @NotNull TransactionType type) {}

    record RuleResponse(UUID id, MatchType matchType, String pattern, TransactionType transactionType, UUID categoryId, UUID contextId,
                        int priority, boolean enabled, Instant createdAt, Instant updatedAt) {
        static RuleResponse of(CategorizationRule r) {
            return new RuleResponse(r.getId(), r.getMatchType(), r.getPattern(), r.getTransactionType(), r.getCategoryId(), r.getContextId(),
                    r.getPriority(), r.isEnabled(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    record PartResponse(UUID id, java.math.BigDecimal confidence, Source source, Decision decision) {
        static PartResponse of(Part p) {
            return new PartResponse(p.id(), p.confidence() == null ? null : p.confidence().toBigDecimal(), p.source(), p.decision());
        }
    }

    record TestResponse(PartResponse category, PartResponse context, Decision decision) {}

    private final CategorizationRuleService service;
    private final ClassificationService classification;

    RuleController(CategorizationRuleService service, ClassificationService classification) {
        this.service = service;
        this.classification = classification;
    }

    @GetMapping
    @Operation(summary = "Lista as regras do usuário, na ordem em que valem (prioridade menor primeiro)")
    List<RuleResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt)).stream().map(RuleResponse::of).toList();
    }

    @PostMapping
    @Operation(summary = "Cria uma regra",
            description = "O padrão é normalizado (sem acento, caixa e pontuação) e comparado por palavras inteiras. "
                    + "Decide categoria, contexto ou os dois. Máximo de 200 regras; a mesma condição não se repete (409).")
    ResponseEntity<RuleResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRuleRequest body) {
        CategorizationRule r = service.create(userId(jwt), body.matchType(), body.pattern(), body.transactionType(), body.categoryId(),
                body.contextId(), body.priority(), body.enabled());
        return ResponseEntity.created(URI.create("/api/v1/categorization-rules/" + r.getId())).body(RuleResponse.of(r));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha uma regra")
    RuleResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return RuleResponse.of(service.get(userId(jwt), id));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edita uma regra", description = "Campo ausente não muda; categoryId, contextId e transactionType com null limpam.")
    RuleResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateRuleRequest body) {
        return RuleResponse.of(service.update(userId(jwt), id, body.f));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga uma regra", description = "Não altera transações já classificadas.")
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(userId(jwt), id);
    }

    @PostMapping("/test")
    @Operation(summary = "Mostra como o motor classificaria um texto agora (regras, favorecidos aprendidos e regras padrão)",
            description = "Não grava nada. Serve para conferir uma regra antes de confiar nela.")
    TestResponse test(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TestRequest body) {
        Result r = classification.classify(classification.snapshot(userId(jwt)), body.description(), body.merchant(), body.type());
        return new TestResponse(PartResponse.of(r.category()), PartResponse.of(r.context()), r.summary().decision());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
