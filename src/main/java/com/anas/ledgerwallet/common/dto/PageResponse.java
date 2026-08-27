package com.anas.ledgerwallet.common.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * A page of results.
 *
 * <p>Defined here rather than returning Spring Data's {@code Page} directly: that
 * type's JSON shape is an implementation detail of the library, not a contract, and
 * serialising it ties the public API to a version of Spring Data.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
