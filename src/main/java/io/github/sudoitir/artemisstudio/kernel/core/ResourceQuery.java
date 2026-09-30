package io.github.sudoitir.artemisstudio.kernel.core;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The shared query envelope for every cross-node list endpoint: a free-text
 * filter, 1-based paging, and an optional {@code sort} of the form
 * {@code field} or {@code -field} (descending). Filtering, sorting and paging
 * all happen in memory after the per-node fan-out (ADR-0017).
 */
public record ResourceQuery(String q, Integer page, Integer size, String sort) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 500;

    /**
     * An absent {@code page} is 1 and an absent {@code size} is {@value #DEFAULT_SIZE}; a {@code page} below 1 or a
     * {@code size} outside 1 to {@value #MAX_SIZE} is refused ({@code invalid-value}), not clamped.
     */
    public ResourceQuery {
        page = page == null ? 1 : page;
        size = size == null ? DEFAULT_SIZE : size;
        if (page < 1) {
            throw new IllegalArgumentException("page starts at 1.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE + ".");
        }
    }

    public static ResourceQuery of(String q, Integer page, Integer size, String sort) {
        return new ResourceQuery(q, page, size, sort);
    }

    /** A query for paging alone, for a list with nothing to search or sort. */
    public static ResourceQuery ofPage(Integer page, Integer size) {
        return new ResourceQuery(null, page, size, null);
    }

    /** The zero-based row offset of this page, for {@code LIMIT/OFFSET}. */
    public int offset() {
        return (page - 1) * size;
    }

    /** Case-insensitive substring match; a blank filter matches everything. */
    public boolean matches(String value) {
        if (q == null || q.isBlank()) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }

    public boolean sortDescending() {
        return sort != null && sort.startsWith("-");
    }

    /** The sort field with any leading {@code -} stripped; {@code null} when no sort was asked for. */
    public String sortField() {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        return sort.startsWith("-") ? sort.substring(1) : sort;
    }

    /** Sort (if a comparator is given), then cut the requested page. */
    public <T> PagedView<T> paginate(List<T> all, Comparator<T> comparator) {
        List<T> ordered = all;
        if (comparator != null && sortField() != null) {
            ordered = all.stream()
                    .sorted(sortDescending() ? comparator.reversed() : comparator)
                    .toList();
        }
        int from = Math.min(offset(), ordered.size());
        int to = Math.min(from + size, ordered.size());
        return new PagedView<>(ordered.subList(from, to), page, size, (long) all.size(), to < ordered.size());
    }
}
