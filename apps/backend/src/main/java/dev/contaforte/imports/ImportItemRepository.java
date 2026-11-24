package dev.contaforte.imports;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportItemRepository extends JpaRepository<ImportItem, UUID> {

    Page<ImportItem> findByImportIdOrderByLineNo(UUID importId, Pageable pageable);

    Page<ImportItem> findByImportIdAndStatusOrderByLineNo(UUID importId, ImportItemStatus status, Pageable pageable);

    /** Linhas que serão criadas na confirmação, na ordem do arquivo. */
    List<ImportItem> findByImportIdAndSelectedTrueOrderByLineNo(UUID importId);

    long countByImportIdAndStatus(UUID importId, ImportItemStatus status);

    /** Linhas que podem virar transação (nova ou possível duplicata), na ordem do arquivo. */
    List<ImportItem> findByImportIdAndStatusInOrderByLineNo(UUID importId, Collection<ImportItemStatus> statuses);
}
