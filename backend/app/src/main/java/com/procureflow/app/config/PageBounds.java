package com.procureflow.app.config;

import com.procureflow.app.shared.ApiException;

/**
 * One shared bounded offset-pagination policy for every list API (requests, notifications and
 * audit). The request/notification APIs keep their historical {@code invalid_page} code and the
 * audit API its {@code invalid_request} code; only the bound itself is shared.
 */
public final class PageBounds {
    public static final int MAX_PAGE = 1_000_000;
    public static final int MAX_SIZE = 100;

    private PageBounds() {}

    /** Validates page/size and returns the row offset, or fails with the caller's error code. */
    public static long offset(int page, int size, String invalidCode) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) throw ApiException.invalid(invalidCode);
        return (long) page * size;
    }
}
