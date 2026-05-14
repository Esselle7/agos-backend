package com.agostinelli.gestionale.shared.dto;

import java.util.List;

/**
 * Wrapper generico per risposte paginate REST.
 * Porta il contenuto tipizzato e i metadati di paginazione.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PagedResponse<>(content, page, size, totalElements, totalPages);
    }
}
