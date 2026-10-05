package com.iot.greenhouse.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Clamps client-supplied paging values so a request cannot ask for the whole table
 * or a negative offset.
 */
public final class PageParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    private PageParams() {
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }

    public static Pageable of(Integer page, Integer size, String... sortProperties) {
        return of(page, size, Sort.by(sortProperties));
    }
}
