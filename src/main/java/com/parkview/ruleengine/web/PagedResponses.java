package com.parkview.ruleengine.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * List endpoints keep returning a plain JSON array (backwards compatible with the frontend) but
 * are always bounded; the total is exposed in {@code X-Total-Count}.
 */
public final class PagedResponses {

    public static final int DEFAULT_SIZE = 100;
    public static final int MAX_SIZE = 500;

    private PagedResponses() {
    }

    public static Pageable pageable(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_SIZE), sort);
    }

    public static <T> ResponseEntity<List<T>> of(Page<T> page) {
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(page.getTotalElements()))
                .body(page.getContent());
    }
}
