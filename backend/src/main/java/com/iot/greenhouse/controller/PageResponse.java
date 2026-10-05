package com.iot.greenhouse.controller;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Envelope returned by every list endpoint.
 *
 * <p>The endpoints used to return a bare JSON array capped at 500 rows, which silently
 * hid data as soon as a table grew past the cap. Callers now receive an explicit page
 * plus the real total so they can tell a short page from an empty one.
 *
 * @param content       rows of the requested page
 * @param page          zero-based page index that was returned
 * @param size          page size that was applied
 * @param totalElements total rows matching the filter across all pages
 * @param totalPages    number of pages at this page size
 * @param first         whether this is the first page
 * @param last          whether this is the last page
 * @param empty         whether content is empty
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements,
                              int totalPages, boolean first, boolean last, boolean empty) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty());
    }
}
