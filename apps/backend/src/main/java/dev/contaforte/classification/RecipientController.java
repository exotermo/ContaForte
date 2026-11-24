package dev.contaforte.classification;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.contaforte.classification.RecipientStore.Row;
import dev.contaforte.classification.RecipientStore.Votes;
import dev.contaforte.common.ApiException;
import dev.contaforte.common.PageResponse;
import dev.contaforte.audit.AuditService;
import dev.contaforte.imports.parse.TextNorm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Favorecidos PIX aprendidos. O nome é dado de terceiro (ADR-018): só do próprio usuário, com "esquecer". */
@RestController
@RequestMapping("/api/v1/pix-recipients")
@Tag(name = "Favorecidos PIX", description = "O que o sistema aprendeu de quem você paga e de quem te paga. Só aprende com decisões suas.")
class RecipientController {

    /** O mais votado de uma dimensão: quantos votos, de quantos, e a confiança calculada. */
    record Learned(UUID id, int votes, int totalVotes, BigDecimal confidence, Decision decision) {
        static Learned of(RecipientKnowledge.Pick p) {
            if (p == null) return null;
            return new Learned(p.id(), p.confidence().num(), p.confidence().den() - 1, p.confidence().toBigDecimal(), p.confidence().decision());
        }
    }

    record Direction(Learned category, Learned context) {
        static Direction of(RecipientKnowledge k) {
            return new Direction(Learned.of(k.topCategory()), Learned.of(k.topContext()));
        }
    }

    record RecipientResponse(UUID id, String displayName, int transactionCount,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal averageAmount, Instant lastTransactionAt,
                             Direction expense, Direction income) {
        static RecipientResponse of(Row r, Votes v) {
            Votes votes = v == null ? new Votes() : v;
            return new RecipientResponse(r.id(), r.displayName(), r.transactionCount(), r.averageAmount(), r.lastTransactionAt(),
                    Direction.of(votes.knowledge("OUT")), Direction.of(votes.knowledge("IN")));
        }
    }

    private final RecipientStore store;
    private final AuditService audit;

    RecipientController(RecipientStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    @GetMapping
    @Operation(summary = "Lista os favorecidos aprendidos, o mais recente primeiro",
            description = "q busca no nome (sem acento e sem diferenciar caixa). 'expense' é o que você paga a ele; 'income' é o que ele te paga.")
    @Transactional(readOnly = true)
    PageResponse<RecipientResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) @Size(max = 100) String q,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID userId = userId(jwt);
        String key = q == null ? "" : TextNorm.key(q);
        if (q != null && !q.isBlank() && key.isEmpty()) return new PageResponse<>(List.of(), page, size, 0, 0);   // só pontuação: não casa nada
        String like = RecipientStore.containsPattern(key);
        long total = store.count(userId, like);
        List<Row> rows = store.list(userId, like, size, (long) page * size);
        Map<UUID, Votes> votes = store.votesOf(rows.stream().map(Row::id).toList());
        List<RecipientResponse> items = rows.stream().map(r -> RecipientResponse.of(r, votes.get(r.id()))).toList();
        return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha um favorecido")
    @Transactional(readOnly = true)
    RecipientResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Row r = store.find(userId(jwt), id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Favorecido não encontrado."));
        return RecipientResponse.of(r, store.votesOf(List.of(id)).get(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Esquece o favorecido e tudo o que foi aprendido dele",
            description = "Não altera as transações já classificadas, só o aprendizado.")
    @Transactional
    void forget(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID userId = userId(jwt);
        if (!store.delete(userId, id)) throw new ApiException(HttpStatus.NOT_FOUND, "Favorecido não encontrado.");
        audit.record(userId, "RECIPIENT_FORGOTTEN", "recipient:" + id, Map.of());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
