package dev.contaforte.imports;

import dev.contaforte.common.PageResponse;
import dev.contaforte.imports.ImportDtos.ConfirmRequest;
import dev.contaforte.imports.ImportDtos.ImportItemResponse;
import dev.contaforte.imports.ImportDtos.ImportResponse;
import dev.contaforte.imports.parse.CsvSuggestion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "Importações", description = "Importação de extratos OFX e CSV em duas fases: prévia, confirmação e rollback lógico. O arquivo não é guardado.")
class ImportController {

    private final ImportService service;

    ImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Lê um extrato e cria a PRÉVIA (nenhuma transação é criada)",
            description = "Multipart: file (até 5 MB), accountId, format (opcional; o formato real vem do conteúdo) e mapping (JSON, só CSV; "
                    + "sem ele, o mapeamento é adivinhado pelo cabeçalho). Limite de 20 prévias por hora. A prévia vence em 24 h.")
    ResponseEntity<ImportResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file,
                                          @RequestParam UUID accountId, @RequestParam(required = false) ImportFormat format,
                                          @RequestParam(required = false) String mapping) {
        ImportResponse created = service.preview(userId(jwt), file, accountId, format, mapping);
        return ResponseEntity.created(URI.create("/api/v1/imports/" + created.id())).body(created);
    }

    @PostMapping(value = "/suggest-mapping", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Sugere o mapeamento de colunas de um CSV (delimitador, cabeçalho, primeiras linhas), sem gravar nada")
    CsvSuggestion suggest(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        return service.suggestMapping(userId(jwt), file);
    }

    @GetMapping
    @Operation(summary = "Histórico de importações, da mais recente para a mais antiga")
    PageResponse<ImportResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(userId(jwt), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalha uma importação")
    ImportResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "Linhas da prévia, na ordem do arquivo, com filtro por status",
            description = "Linhas de duplicata e possível duplicata trazem a transação existente com a qual foram comparadas.")
    PageResponse<ImportItemResponse> items(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @RequestParam(required = false) ImportItemStatus status,
                                           @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return service.items(userId(jwt), id, status, page, size);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirma a prévia: cria as transações das linhas novas e das possíveis duplicatas aceitas",
            description = "Corpo opcional: includePossible (ids de possíveis duplicatas a importar) e exclude (ids de linhas novas a pular). "
                    + "Confirmar duas vezes responde 409; prévia vencida, 410.")
    ImportResponse confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody(required = false) ConfirmRequest body) {
        return service.confirm(userId(jwt), id, body);
    }

    @PostMapping("/{id}/rollback")
    @Operation(summary = "Desfaz uma importação confirmada (exclusão lógica das transações dela)")
    ImportResponse rollback(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.rollback(userId(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Descarta uma prévia")
    void discard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.discard(userId(jwt), id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
