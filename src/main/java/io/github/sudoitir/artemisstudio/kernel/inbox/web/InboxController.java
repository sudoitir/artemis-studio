package io.github.sudoitir.artemisstudio.kernel.inbox.web;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.inbox.InboxService;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own inbox. Every call acts on the caller's notices only; another user's notice
 * is not found, not forbidden, so ids reveal nothing.
 */
@RestController
@RequestMapping("/inbox")
@RequiredArgsConstructor
public class InboxController {

    private static final int MAX_LIMIT = 100;

    private final InboxService inbox;

    @GetMapping
    public InboxViews.PageView list(
            @AuthenticationPrincipal StudioPrincipal me,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return InboxViews.PageView.of(inbox.list(me.userId(), unread, before, limit));
    }

    @GetMapping("/count")
    public InboxViews.CountView count(@AuthenticationPrincipal StudioPrincipal me) {
        InboxService.Count count = inbox.count(me.userId());
        return new InboxViews.CountView(count.unread(), count.capped());
    }

    @PostMapping("/read")
    public InboxViews.ReadView read(
            @AuthenticationPrincipal StudioPrincipal me, @RequestBody InboxViews.ReadRequest request) {
        UUID userId = me.userId();
        if ((request.ids() == null) == (request.upTo() == null)) {
            throw new IllegalArgumentException("Give exactly one of ids and upTo.");
        }
        if (request.upTo() != null) {
            return new InboxViews.ReadView(inbox.markReadUpTo(userId, request.upTo()));
        }
        if (request.ids().size() > MAX_LIMIT || request.ids().contains(null)) {
            throw new IllegalArgumentException("ids holds up to " + MAX_LIMIT + " notice ids.");
        }
        return new InboxViews.ReadView(inbox.markRead(userId, request.ids()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal StudioPrincipal me, @PathVariable long id) {
        if (!inbox.delete(me.userId(), id)) {
            throw new NotFoundException("Notice", id);
        }
    }
}
