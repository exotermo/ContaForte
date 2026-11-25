package dev.contaforte.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.contaforte.accounts.Account;
import dev.contaforte.accounts.AccountRepository;
import dev.contaforte.audit.AuditService;
import dev.contaforte.categories.CategoryRepository;
import dev.contaforte.categories.ContextRepository;
import dev.contaforte.classification.ClassificationMetrics;
import dev.contaforte.classification.ClassificationService;
import dev.contaforte.classification.Decision;
import dev.contaforte.classification.Result;
import dev.contaforte.classification.Source;
import dev.contaforte.common.ApiException;
import dev.contaforte.common.PageResponse;
import dev.contaforte.imports.ImportDtos.ConfirmRequest;
import dev.contaforte.imports.ImportDtos.ExistingSnapshot;
import dev.contaforte.imports.ImportDtos.ImportItemResponse;
import dev.contaforte.imports.ImportDtos.ItemClassification;
import dev.contaforte.imports.ImportDtos.ItemClassificationRequest;
import dev.contaforte.imports.ImportDtos.ImportResponse;
import dev.contaforte.imports.dedup.Classification;
import dev.contaforte.imports.dedup.ImportClassifier;
import dev.contaforte.imports.parse.ContentSniffer;
import dev.contaforte.imports.parse.CsvMapping;
import dev.contaforte.imports.parse.CsvParser;
import dev.contaforte.imports.parse.CsvSuggestion;
import dev.contaforte.imports.parse.ImportParseException;
import dev.contaforte.imports.parse.OfxParser;
import dev.contaforte.imports.parse.ParseResult;
import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.Transaction;
import dev.contaforte.transactions.TransactionRecorded;
import dev.contaforte.transactions.TransactionRepository;
import dev.contaforte.transactions.TransactionSource;
import dev.contaforte.transactions.TransactionType;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importação de extratos em duas fases (ADR-015): a prévia lê, normaliza e classifica o arquivo sem criar transação
 * alguma; a confirmação cria as transações; o rollback as exclui logicamente. Tudo é escopado ao usuário do token
 * (importação de outro usuário é indistinguível de inexistente, 404). O arquivo nunca é guardado, e nome do arquivo,
 * descrições e valores não vão para logs, métricas nem auditoria, que registram só ids e contagens.
 */
@Service
public class ImportService {

    private static final Duration PREVIEW_TTL = Duration.ofHours(24);
    private static final int IN_CHUNK = 500;
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ImportBatchRepository imports;
    private final ImportItemRepository items;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final ImportClassifier classifier;
    private final OfxParser ofx;
    private final CsvParser csv;
    private final ImportRateLimiter limiter;
    private final ImportMetrics metrics;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ClassificationService classification;
    private final ClassificationMetrics classificationMetrics;
    private final CategoryRepository categories;
    private final ContextRepository contexts;

    ImportService(ImportBatchRepository imports, ImportItemRepository items, AccountRepository accounts,
                  TransactionRepository transactions, ImportClassifier classifier, OfxParser ofx, CsvParser csv,
                  ImportRateLimiter limiter, ImportMetrics metrics, AuditService audit, MeterRegistry meters,
                  ApplicationEventPublisher events, Clock clock, ObjectMapper mapper, PlatformTransactionManager txManager,
                  ClassificationService classification, ClassificationMetrics classificationMetrics, CategoryRepository categories, ContextRepository contexts) {
        this.imports = imports;
        this.items = items;
        this.accounts = accounts;
        this.transactions = transactions;
        this.classifier = classifier;
        this.ofx = ofx;
        this.csv = csv;
        this.limiter = limiter;
        this.metrics = metrics;
        this.audit = audit;
        this.meters = meters;
        this.events = events;
        this.clock = clock;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.classification = classification;
        this.classificationMetrics = classificationMetrics;
        this.categories = categories;
        this.contexts = contexts;
    }

    // ---------------------------------------------------------------- prévia

    /**
     * Lê o arquivo e grava a prévia. A leitura (CPU) acontece FORA de transação do banco; só a gravação das linhas é
     * transacional.
     */
    public ImportResponse preview(UUID userId, MultipartFile file, UUID accountId, ImportFormat declared, String mappingJson) {
        limiter.checkPreview(userId);
        Account account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conta não encontrada."));
        if (account.isArchived()) throw new ApiException(HttpStatus.CONFLICT, "Conta arquivada não recebe importações.");

        long started = System.nanoTime();
        byte[] bytes = read(file);
        ImportFormat format = detect(bytes, declared);

        ParseResult parsed;
        Map<String, String> usedMapping = null;
        try {
            if (format == ImportFormat.OFX) {
                parsed = ofx.parse(bytes);
            } else {
                CsvMapping mapping = csvMapping(bytes, mappingJson);
                usedMapping = mapping.toMap();
                parsed = csv.parse(bytes, mapping);
            }
        } catch (ImportParseException e) {
            throw reject(e, format.name());
        }
        Classification dedup = classifier.classify(accountId, parsed);

        String sha = sha256(bytes);
        boolean already = imports.existsByAccountIdAndFileSha256AndStatus(accountId, sha, ImportStatus.CONFIRMED);
        ImportBatch batch = new ImportBatch(userId, accountId, format, cleanFileName(file.getOriginalFilename()), sha, bytes.length,
                Instant.now(clock).plus(PREVIEW_TTL));
        batch.setCounts(dedup.count(ImportItemStatus.NEW), dedup.count(ImportItemStatus.DUPLICATE),
                dedup.count(ImportItemStatus.POSSIBLE_DUPLICATE), dedup.count(ImportItemStatus.INVALID));
        batch.setMapping(usedMapping);

        // a classificação das linhas (CPU) também acontece fora da transação: o snapshot do usuário é lido uma vez
        ClassificationService.Snapshot snapshot = this.classification.snapshot(userId);
        ClassificationMetrics.Tally tally = classificationMetrics.tally();
        ImportBatch saved = tx.execute(status -> {
            ImportBatch b = imports.save(batch);
            List<ImportItem> rows = ImportClassifier.toItems(b.getId(), dedup);
            for (ImportItem i : rows) {
                if (i.getStatus() != ImportItemStatus.NEW && i.getStatus() != ImportItemStatus.POSSIBLE_DUPLICATE) continue;
                dev.contaforte.classification.Result decided = this.classification.classify(snapshot, i.getDescription(), i.getMerchant(), i.getType());
                i.setEngine(decided);
                tally.add(decided);
            }
            items.saveAll(rows);
            return b;
        });

        tally.flush();   // só depois de gravar: uma prévia que falhou não conta
        metrics.imports(format.name(), "PREVIEW");
        metrics.rows("NEW", saved.getNewRows());
        metrics.rows("DUPLICATE", saved.getDuplicateRows());
        metrics.rows("POSSIBLE_DUPLICATE", saved.getPossibleRows());
        metrics.rows("INVALID", saved.getInvalidRows());
        metrics.previewDuration(format.name(), Duration.ofNanos(System.nanoTime() - started));
        audit.record(userId, "IMPORT_PREVIEWED", "import:" + saved.getId(), Map.of("format", format.name(), "total", saved.getTotalRows(),
                "new", saved.getNewRows(), "duplicate", saved.getDuplicateRows(), "possible", saved.getPossibleRows(),
                "invalid", saved.getInvalidRows()));
        return ImportResponse.of(saved).withPreviewInfo(already, parsed.warnings())
                .withClassification((int) items.countByEngineDecision(saved.getId(), "AUTO"), (int) items.countByEngineDecision(saved.getId(), "SUGGEST"));
    }

    /** Palpite de mapeamento de um CSV, sem gravar nada. */
    public CsvSuggestion suggestMapping(UUID userId, MultipartFile file) {
        limiter.checkSuggestion(userId);
        byte[] bytes = read(file);
        ImportFormat format = detect(bytes, ImportFormat.CSV);
        try {
            return csv.suggest(bytes);
        } catch (ImportParseException e) {
            throw reject(e, format.name());
        }
    }

    // ---------------------------------------------------------------- consulta

    @Transactional(readOnly = true)
    public PageResponse<ImportResponse> list(UUID userId, int page, int size) {
        return PageResponse.of(imports.findByUserId(userId, PageRequest.of(page, size, NEWEST)).map(ImportResponse::of));
    }

    @Transactional(readOnly = true)
    public ImportResponse get(UUID userId, UUID id) {
        ImportBatch b = find(userId, id);
        ImportResponse r = ImportResponse.of(b);
        if (b.getStatus() != ImportStatus.PREVIEW) return r;
        return r.withClassification((int) items.countByEngineDecision(id, "AUTO"), (int) items.countByEngineDecision(id, "SUGGEST"));
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportItemResponse> items(UUID userId, UUID id, ImportItemStatus status, int page, int size) {
        ImportBatch batch = find(userId, id);
        PageRequest paging = PageRequest.of(page, size);
        Page<ImportItem> found = status == null ? items.findByImportIdOrderByLineNo(batch.getId(), paging)
                : items.findByImportIdAndStatusOrderByLineNo(batch.getId(), status, paging);

        // a transação com a qual cada linha foi comparada, para o usuário decidir (só as da conta desta importação)
        Set<UUID> ids = new HashSet<>();
        for (ImportItem i : found) if (i.getDuplicateOf() != null) ids.add(i.getDuplicateOf());
        Map<UUID, Transaction> existing = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Transaction t : transactions.findAllById(ids)) {
                boolean sameAccount = batch.getAccountId().equals(t.getAccountId()) || batch.getAccountId().equals(t.getCounterpartAccountId());
                if (t.getUserId().equals(userId) && sameAccount) existing.put(t.getId(), t);
            }
        }
        return PageResponse.of(found.map(i -> {
            Transaction t = existing.get(i.getDuplicateOf());
            ExistingSnapshot snap = t == null ? null : new ExistingSnapshot(t.getId(), t.getOccurredAt(), t.getType(), t.getAmount(),
                    t.getDescription(), t.getSource());
            return new ImportItemResponse(i.getId(), i.getLineNo(), i.getStatus(), i.getOccurredAt(), i.getType(), i.getAmount(),
                    i.getDescription(), i.getMerchant(), i.getReason(), i.isSelected(), i.getDuplicateOf(), snap, itemClassification(i));
        }));
    }

    private static ItemClassification itemClassification(ImportItem i) {
        if (i.getStatus() != ImportItemStatus.NEW && i.getStatus() != ImportItemStatus.POSSIBLE_DUPLICATE) return null;
        return new ItemClassification(i.getEngineDecision() == null ? Decision.NONE : Decision.valueOf(i.getEngineDecision()),
                i.getEngineSource() == null ? null : Source.valueOf(i.getEngineSource()), i.getEngineConfidence(),
                i.getEngineCategoryId(), i.getEngineCategoryDecision() == null ? null : Decision.valueOf(i.getEngineCategoryDecision()),
                i.getEngineContextId(), i.getEngineContextDecision() == null ? null : Decision.valueOf(i.getEngineContextDecision()),
                i.isChosen(), i.getChosenCategoryId(), i.getChosenContextId());
    }

    // ---------------------------------------------------------------- classificação de uma linha da prévia

    /**
     * O usuário troca categoria e contexto de uma linha antes de confirmar (ou volta ao que o motor decidiu com
     * {@code reset}). Só vale em prévia, e só para linha nova ou possível duplicata. Nada é aprendido ainda: o aprendizado
     * acontece na confirmação, e é desfeito se a importação for desfeita.
     */
    @Transactional
    public ImportItemResponse classifyItem(UUID userId, UUID importId, UUID itemId, ItemClassificationRequest req) {
        ImportBatch batch = imports.findForUpdate(importId, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Importação não encontrada."));
        if (batch.getStatus() != ImportStatus.PREVIEW) throw new ApiException(HttpStatus.CONFLICT, "Só a prévia pode ser alterada.");
        ImportItem item = items.findByIdAndImportId(itemId, importId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Linha não encontrada."));
        if (item.getStatus() != ImportItemStatus.NEW && item.getStatus() != ImportItemStatus.POSSIBLE_DUPLICATE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Só linhas novas ou possíveis duplicatas podem ser classificadas.");
        }
        if (req.reset()) {
            item.resetChoice();
        } else {
            UUID cat = req.categoryId() == null ? null
                    : categories.findVisibleById(req.categoryId(), userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Categoria não encontrada.")).getId();
            UUID ctx = req.contextId() == null ? null
                    : contexts.findVisibleById(req.contextId(), userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contexto não encontrado.")).getId();
            item.choose(cat, ctx);
        }
        items.saveAndFlush(item);
        return new ImportItemResponse(item.getId(), item.getLineNo(), item.getStatus(), item.getOccurredAt(), item.getType(), item.getAmount(),
                item.getDescription(), item.getMerchant(), item.getReason(), item.isSelected(), item.getDuplicateOf(), null, itemClassification(item));
    }

    // ---------------------------------------------------------------- confirmação

    /**
     * Cria as transações da prévia: as linhas NOVAS (menos as que o usuário excluiu) e as POSSÍVEIS duplicatas que ele
     * aceitou. A prévia fica bloqueada durante a operação, então duas confirmações simultâneas nunca criam em dobro.
     * Antes de gravar, confere de novo os identificadores externos: o que virou duplicata desde a prévia (por exemplo,
     * por outra importação) é pulado e contado em {@code skippedByConflict}, em vez de derrubar a confirmação inteira.
     */
    @Transactional
    public ImportResponse confirm(UUID userId, UUID id, ConfirmRequest req) {
        ImportBatch batch = imports.findForUpdate(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Importação não encontrada."));
        if (batch.getStatus() != ImportStatus.PREVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "Esta importação já foi confirmada ou desfeita.");
        }
        Instant now = Instant.now(clock);
        if (batch.getExpiresAt() != null && batch.getExpiresAt().isBefore(now)) {
            throw new ApiException(HttpStatus.GONE, "A prévia expirou. Envie o arquivo de novo.");
        }
        Account account = accounts.findByIdAndUserId(batch.getAccountId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Conta não encontrada."));
        if (account.isArchived()) throw new ApiException(HttpStatus.CONFLICT, "Conta arquivada não recebe importações.");

        List<ImportItem> candidates = items.findByImportIdAndStatusInOrderByLineNo(id, List.of(ImportItemStatus.NEW, ImportItemStatus.POSSIBLE_DUPLICATE));
        Map<UUID, ImportItem> byId = new HashMap<>();
        for (ImportItem i : candidates) byId.put(i.getId(), i);
        Set<UUID> include = idSet(req == null ? null : req.includePossible());
        Set<UUID> exclude = idSet(req == null ? null : req.exclude());
        for (UUID u : include) {
            ImportItem i = byId.get(u);
            if (i == null || i.getStatus() != ImportItemStatus.POSSIBLE_DUPLICATE) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "includePossible só aceita linhas de possível duplicata desta importação.");
            }
        }
        for (UUID u : exclude) {
            ImportItem i = byId.get(u);
            if (i == null || i.getStatus() != ImportItemStatus.NEW) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "exclude só aceita linhas novas desta importação.");
            }
        }

        List<ImportItem> chosen = new ArrayList<>();
        for (ImportItem i : candidates) {
            boolean take = i.getStatus() == ImportItemStatus.NEW ? !exclude.contains(i.getId()) : include.contains(i.getId());
            i.setSelected(take);
            if (take) chosen.add(i);
        }

        Set<String> alreadyThere = existingExternalIds(account.getId(), chosen);
        Set<String> usedNow = new HashSet<>();
        List<Transaction> toCreate = new ArrayList<>();
        Map<Transaction, ClassificationService.Prediction> predictions = new java.util.IdentityHashMap<>();
        int converted = 0;
        TransactionSource source = batch.getFormat() == ImportFormat.OFX ? TransactionSource.OFX : TransactionSource.CSV;
        for (ImportItem i : chosen) {
            String ext = i.getExternalId();
            if (ext != null && (alreadyThere.contains(ext) || !usedNow.add(ext))) {
                i.setStatus(ImportItemStatus.DUPLICATE);
                i.setSelected(false);
                i.setReason("Identificador do extrato já importado nesta conta");
                converted++;
                continue;
            }
            Transaction t = new Transaction(userId, account.getId(), i.getType(), i.getAmount(), i.getOccurredAt());
            t.setDescription(i.getDescription());
            t.setMerchant(i.getMerchant());
            t.markImported(batch.getId(), ext, source);
            Optional<ClassificationService.Prediction> predicted = Optional.empty();
            if (i.getEngineDecision() != null) {
                predicted = Optional.of(new ClassificationService.Prediction(i.getEngineCategoryId(), i.getEngineContextId(), i.getEngineSource(), i.getEngineConfidence()));
            }
            if (i.isChosen()) {
                classification.applyUserChoice(t, i.getChosenCategoryId(), i.getChosenContextId());
            } else if (i.getEngineDecision() != null) {
                classification.applyStored(t, i.getEngineCategoryId(), decision(i.getEngineCategoryDecision()), i.getEngineContextId(),
                        decision(i.getEngineContextDecision()), i.getEngineSource(), i.getEngineConfidence());
            }
            predictions.put(t, predicted.orElse(null));
            toCreate.add(t);
        }
        try {
            transactions.saveAll(toCreate);
            transactions.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Houve um conflito ao importar (outra importação mexeu na conta). Envie o arquivo de novo.");
        }
        // o que o usuário decidiu na prévia ensina os favorecidos (e gera feedback onde havia previsão)
        for (Transaction t : toCreate) classification.learnImported(userId, t, predictions.get(t));

        int newRows = 0;
        int possibleRows = 0;
        for (ImportItem i : candidates) {
            if (i.getStatus() == ImportItemStatus.NEW) newRows++;
            else if (i.getStatus() == ImportItemStatus.POSSIBLE_DUPLICATE) possibleRows++;
        }
        batch.setCounts(newRows, batch.getDuplicateRows() + converted, possibleRows, batch.getInvalidRows());
        batch.setStatus(ImportStatus.CONFIRMED);
        batch.setConfirmedAt(now);
        batch.setImportedRows(toCreate.size());
        items.saveAll(candidates);
        imports.save(batch);

        Map<TransactionType, Integer> byType = new EnumMap<>(TransactionType.class);
        for (Transaction t : toCreate) {
            byType.merge(t.getType(), 1, Integer::sum);
            events.publishEvent(new TransactionRecorded(t.getId(), userId, account.getId(), t.getType()));
        }
        byType.forEach((type, n) -> meters.counter("contaforte.transactions.recorded", "type", type.name()).increment(n));
        metrics.imports(batch.getFormat().name(), "CONFIRMED");
        metrics.rows("IMPORTED", toCreate.size());
        audit.record(userId, "IMPORT_CONFIRMED", "import:" + id, Map.of("imported", toCreate.size(), "skippedByConflict", converted));
        return ImportResponse.of(batch).withSkipped(converted);
    }

    // ---------------------------------------------------------------- desfazer e descartar

    /** Rollback lógico: exclui (logicamente) as transações da importação que ainda estão ativas. O histórico permanece. */
    @Transactional
    public ImportResponse rollback(UUID userId, UUID id) {
        ImportBatch locked = imports.findForUpdate(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Importação não encontrada."));
        if (locked.getStatus() != ImportStatus.CONFIRMED) {
            throw new ApiException(HttpStatus.CONFLICT, "Só uma importação confirmada pode ser desfeita.");
        }
        Instant now = Instant.now(clock);
        int reverted = classification.revertLearning(userId, id);   // antes da exclusão: precisa ler as transações ativas
        int affected = transactions.softDeleteByImport(id, now);
        ImportBatch batch = imports.findById(id).orElseThrow();   // recarrega: a atualização em lote limpou o contexto
        batch.setStatus(ImportStatus.ROLLED_BACK);
        batch.setRolledBackAt(now);
        imports.save(batch);
        metrics.imports(batch.getFormat().name(), "ROLLED_BACK");
        audit.record(userId, "IMPORT_ROLLED_BACK", "import:" + id, Map.of("transactions", affected, "learningReverted", reverted));
        return ImportResponse.of(batch);
    }

    /** Descarta uma prévia (nada foi criado, então nada precisa ser desfeito). */
    @Transactional
    public void discard(UUID userId, UUID id) {
        ImportBatch batch = imports.findForUpdate(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Importação não encontrada."));
        if (batch.getStatus() != ImportStatus.PREVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "Só uma prévia pode ser descartada; uma importação confirmada é desfeita.");
        }
        imports.delete(batch);
        metrics.imports(batch.getFormat().name(), "DISCARDED");
        audit.record(userId, "IMPORT_DISCARDED", "import:" + id, Map.of());
    }

    /** Apaga as prévias vencidas. Devolve quantas. Chamado pela tarefa agendada. */
    @Transactional
    public int purgeExpiredPreviews() {
        int n = imports.deleteExpired(ImportStatus.PREVIEW, Instant.now(clock));
        // o formato de cada uma não é lido (seria uma consulta a mais): o total vai na série UNKNOWN
        metrics.imports("UNKNOWN", "EXPIRED", n);
        return n;
    }

    // ---------------------------------------------------------------- apoio

    private ImportBatch find(UUID userId, UUID id) {
        return imports.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Importação não encontrada."));
    }

    private byte[] read(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O arquivo está vazio.");
            return bytes;
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Não foi possível ler o arquivo enviado.");
        }
    }

    /** O formato vem do CONTEÚDO; o declarado só serve para recusar uma divergência clara. */
    private ImportFormat detect(byte[] bytes, ImportFormat declared) {
        ImportFormat sniffed = ContentSniffer.detect(bytes).orElseThrow(() -> {
            metrics.imports("UNKNOWN", "REJECTED");
            return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O arquivo não parece um extrato OFX ou CSV.");
        });
        if (declared != null && declared != sniffed) {
            metrics.imports(sniffed.name(), "REJECTED");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O arquivo enviado parece " + sniffed + ", não " + declared + ".");
        }
        return sniffed;
    }

    private CsvMapping csvMapping(byte[] bytes, String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            CsvSuggestion s = csv.suggest(bytes);
            if (s.mapping().isEmpty()) {
                throw new ImportParseException(ImportParseException.Reason.BAD_MAPPING,
                        "Não consegui identificar as colunas do CSV: informe o mapeamento (data, valor e descrição).");
            }
            return CsvMapping.fromMap(s.mapping());
        }
        Map<String, String> map = new LinkedHashMap<>();
        try {
            JsonNode node = mapper.readTree(mappingJson);
            if (node == null || !node.isObject()) throw new ImportParseException(ImportParseException.Reason.BAD_MAPPING, "O mapeamento deve ser um objeto JSON.");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                JsonNode v = f.getValue();
                if (v.isNull()) continue;
                if (!v.isValueNode()) throw new ImportParseException(ImportParseException.Reason.BAD_MAPPING, "Valor inválido em " + f.getKey() + ".");
                map.put(f.getKey(), v.asText());   // aceita número e booleano (skipLines: 3, hasHeader: false)
            }
        } catch (JsonProcessingException e) {
            throw new ImportParseException(ImportParseException.Reason.BAD_MAPPING, "O mapeamento não é um JSON válido.");
        }
        return CsvMapping.fromMap(map);
    }

    /** Erro do arquivo vira 422 (ou 413 se grande demais); a mensagem é estática e nunca ecoa o conteúdo. */
    private ApiException reject(ImportParseException e, String format) {
        metrics.imports(format, "REJECTED");
        HttpStatus status = e.reason() == ImportParseException.Reason.TOO_LARGE ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.UNPROCESSABLE_ENTITY;
        return new ApiException(status, e.getMessage());
    }

    private Set<String> existingExternalIds(UUID accountId, List<ImportItem> chosen) {
        List<String> ids = new ArrayList<>();
        for (ImportItem i : chosen) if (i.getExternalId() != null) ids.add(i.getExternalId());
        Set<String> found = new HashSet<>();
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            for (Transaction t : transactions.findActiveByAccountAndExternalIds(accountId, ids.subList(from, Math.min(ids.size(), from + IN_CHUNK)))) {
                found.add(t.getExternalId());
            }
        }
        return found;
    }

    private static Decision decision(String name) {
        return name == null ? Decision.NONE : Decision.valueOf(name);
    }

    private static Set<UUID> idSet(Collection<UUID> ids) {
        Set<UUID> s = new HashSet<>();
        if (ids != null) for (UUID u : ids) if (u != null) s.add(u);
        return s;
    }

    /** Só o nome, sem caminho, sem controle e com limite de tamanho. */
    static String cleanFileName(String original) {
        if (original == null) return "extrato";
        String n = original;
        int cut = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (cut >= 0) n = n.substring(cut + 1);
        String clean = TextNorm.clean(n, 255);
        return clean == null ? "extrato" : clean;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
