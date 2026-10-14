package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.core.Cursor;
import java.time.Instant;

/**
 * The filters and page of an audit-log read; a blank or null filter matches everything.
 *
 * @param username who acted
 * @param action the audit action
 * @param outcome the recorded outcome
 * @param parentId the event a batch's children belong to
 * @param from earliest timestamp, inclusive
 * @param to latest timestamp, inclusive
 * @param page 1-based page number
 * @param size rows per page, at most 500
 * @param after where the previous page ended, or null to page by number
 */
public record AuditQuery(
        String username,
        String action,
        String outcome,
        Long parentId,
        Instant from,
        Instant to,
        int page,
        int size,
        Cursor after) {}
