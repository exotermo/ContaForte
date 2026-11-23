package dev.contaforte.summary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/summary")
@Tag(name = "Resumo", description = "Posição financeira atual e movimentação mensal")
class SummaryController {

    private final SummaryService service;

    SummaryController(SummaryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Posição atual: caixa disponível, saldo restrito (VA/VR), investido, cartão e patrimônio líquido",
            description = "Só contas ativas. VA/VR nunca entram no caixa disponível.")
    SummaryResponse current(@AuthenticationPrincipal Jwt jwt) {
        return service.current(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/monthly")
    @Operation(summary = "Movimentação do mês: receitas, gastos, estornos, investido e distribuição por categoria e contexto",
            description = "month no formato AAAA-MM (padrão: mês atual em America/Sao_Paulo). Transferências e "
                    + "investimentos não contam como receita nem gasto.")
    MonthlySummaryResponse monthly(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.monthly(UUID.fromString(jwt.getSubject()), month);
    }
}
