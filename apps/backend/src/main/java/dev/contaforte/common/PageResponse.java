package dev.contaforte.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** Página de resultados com contagem total. {@code page} é base 0. */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
