package io.github.sudoitir.artemisstudio.kernel.inbox.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.inbox.InboxItem;
import io.github.sudoitir.artemisstudio.kernel.inbox.InboxService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The wire shapes of the inbox API. */
public final class InboxViews {

    private InboxViews() {}

    public record ItemView(
            @Schema(requiredMode = REQUIRED) long id,

            @Schema(requiredMode = REQUIRED, description = "Who posted it: a Studio feature or a plugin id.")
            String source,

            @Schema(requiredMode = REQUIRED) String kind,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"info", "success", "warning", "danger"})
            String severity,

            @Schema(requiredMode = REQUIRED) String title,
            @Schema(nullable = true) String body,

            @Schema(nullable = true, description = "A path inside Studio.")
            String link,

            @Schema(nullable = true) Map<String, String> data,
            @Schema(requiredMode = REQUIRED) Instant createdAt,

            @Schema(nullable = true, description = "Null while unread.")
            Instant readAt) {

        static ItemView of(InboxItem item) {
            return new ItemView(
                    item.id(),
                    item.source(),
                    item.kind(),
                    item.severity().wire(),
                    item.title(),
                    item.body(),
                    item.link(),
                    item.data(),
                    item.createdAt(),
                    item.readAt());
        }
    }

    public record PageView(
            @Schema(requiredMode = REQUIRED) List<ItemView> items,

            @Schema(nullable = true, description = "Pass as `before` for the next page; null on the last page.")
            Long next) {

        static PageView of(InboxService.Page page) {
            return new PageView(page.items().stream().map(ItemView::of).toList(), page.next());
        }
    }

    public record CountView(
            @Schema(requiredMode = REQUIRED) int unread,

            @Schema(requiredMode = REQUIRED, description = "True when there are at least this many; show 99+.")
            boolean capped) {}

    public record ReadRequest(
            @Schema(nullable = true, description = "The notices to mark read. Exactly one of ids and upTo.")
            List<Long> ids,

            @Schema(nullable = true, description = "Mark every notice with an id up to this one read.")
            Long upTo) {}

    public record ReadView(@Schema(requiredMode = REQUIRED) int updated) {}
}
