package dev.contaforte.classification;

import dev.contaforte.classification.ClassificationService.Action;
import dev.contaforte.classification.ClassificationService.ReclassifyOutcome;
import dev.contaforte.classification.ClassificationService.ReclassifyPreview;
import dev.contaforte.classification.ClassificationService.ReviewCounts;
import dev.contaforte.classification.ClassificationService.RuleDraft;
import dev.contaforte.common.PageResponse;
import dev.contaforte.transactions.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Classificação", description = "Fila de revisão, decisões do usuário e reclassificação em duas fases (ADR-017).")
class ClassificationController {

    record RuleDraftRequest(@NotNull MatchType matchType, @NotNull @Size(min = 1, max = 200) String pattern) {}

    /** CONFIRM aceita a sugestão como está; CORRECT define categoria e/ou contexto (ao menos um). */
    record DecisionRequest(@NotNull Action action, UUID categoryId, UUID contextId, @Valid RuleDraftRequest createRule) {}

    record ReclassifyRequest(@Min(0) int expectedAuto, @Min(0) int expectedSuggest) {}

    private final ClassificationService service;

    ClassificationController(ClassificationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/classification/review")
    @Operation(summary = "Fila de revisão: sugestões pendentes e transações sem categoria (receitas e despesas)",
            description = "Mais recentes primeiro. Cada item é uma transação, com a sugestão em suggestedCategoryId/suggestedContextId.")
    PageResponse<TransactionResponse> review(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(service.reviewQueue(userId(jwt), page, size));
    }

    @GetMapping("/api/v1/classification/review/count")
    @Operation(summary = "Quantas transações esperam revisão (para o contador do menu)")
    ReviewCounts count(@AuthenticationPrincipal Jwt jwt) {
        return service.reviewCounts(userId(jwt));
    }

    @PostMapping("/api/v1/transactions/{id}/classification")
    @Operation(summary = "Decide a classificação de uma transação: confirma a sugestão ou corrige",
            description = "Só receitas e despesas. A decisão ensina o favorecido (se for PIX) e grava feedback quando havia previsão do motor. "
                    + "createRule cria também uma regra 'sempre assim' cujo padrão precisa casar com a transação.")
    TransactionResponse decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        RuleDraft draft = body.createRule() == null ? null : new RuleDraft(body.createRule().matchType(), body.createRule().pattern());
        return service.decide(userId(jwt), id, body.action(), body.categoryId(), body.contextId(), draft);
    }

    @PostMapping("/api/v1/classification/reclassify/preview")
    @Operation(summary = "Prévia da reclassificação: o que seria aplicado ou sugerido nas transações sem categoria",
            description = "Não grava nada. Nunca toca em transação que o usuário já classificou. Até 20 mil transações por vez.")
    ReclassifyPreview reclassifyPreview(@AuthenticationPrincipal Jwt jwt) {
        return service.previewReclassify(userId(jwt));
    }

    @PostMapping("/api/v1/classification/reclassify")
    @Operation(summary = "Aplica a reclassificação, se o resultado ainda for o da prévia (senão 409)",
            description = "Informe expectedAuto e expectedSuggest da prévia: o que você viu é o que se aplica.")
    ReclassifyOutcome reclassify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReclassifyRequest body) {
        return service.reclassify(userId(jwt), body.expectedAuto(), body.expectedSuggest());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
