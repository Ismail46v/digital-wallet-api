package com.wallet.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON contract for paginated responses. Serializing Spring's {@code PageImpl} directly is not a stable API.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
