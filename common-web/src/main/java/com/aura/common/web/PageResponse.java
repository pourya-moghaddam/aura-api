package com.aura.common.web;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable, hand-shaped wrapper around {@link Page} for API responses.
 *
 * <p>Serializing a Spring Data {@code Page} directly works, but Jackson has to reflect into
 * {@code PageImpl}'s constructor to do it and logs a warning about it on every startup; the exact
 * JSON shape it produces is also an implementation detail of whichever Spring Data version happens
 * to be on the classpath, not a contract. This is what the client actually gets instead.
 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
