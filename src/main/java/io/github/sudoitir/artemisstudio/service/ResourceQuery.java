package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The shared query envelope for every cross-node list endpoint: a free-text
 * filter, 1-based paging, and an optional {@code sort} of the form
 * {@code field} or {@code -field} (descending). Filtering, sorting and paging
 * all happen in memory after the per-node fan-out (ADR-0017).
 */
public record ResourceQuery(String q, int page, int size, String sort) {

    public ResourceQuery {
        page = page < 1 ? 1 : page;
        size = size < 1 ? 50 : Math.min(size, 500);
    }

    public static ResourceQuery of(String q, Integer page, Integer size, String sort) {
        return new ResourceQuery(q, page == null ? 1 : page, size == null ? 50 : size, sort);
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
        int from = Math.min((page - 1) * size, ordered.size());
        int to = Math.min(from + size, ordered.size());
        return new PagedView<>(ordered.subList(from, to), all.size(), page, size);
    }
}
