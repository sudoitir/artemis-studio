package io.github.sudoitir.artemisstudio.kernel.inbox;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Collection;
import java.util.UUID;

/**
 * Posts notices to users' inboxes. A plugin receives its own instance, bound to its id: every notice it
 * posts has the plugin as its source, and it can only {@link #resolve} what it posted itself.
 */
@PluginApi
public interface Inbox {

    /** The most users one post may address. */
    int MAX_RECIPIENTS = 500;

    /**
     * Posts {@code notice} to each of {@code recipients} (unknown users are skipped). A notice that shares
     * a dedupe key with an earlier one to the same user replaces it and is unread again.
     *
     * @return how many users received it
     * @throws IllegalArgumentException when there are more than {@link #MAX_RECIPIENTS} recipients
     */
    int post(Notice notice, Collection<UUID> recipients);

    /**
     * Posts {@code notice} to each enabled user who holds {@code permission} on {@code clusterId} (globally
     * when null), except {@code exclude}. At most {@link #MAX_RECIPIENTS} are notified.
     *
     * @return how many users received it
     */
    int postToHolders(Notice notice, String permission, UUID clusterId, Collection<UUID> exclude);

    /**
     * Marks as read, and retitles to {@code newTitle}, every unread notice of this source with
     * {@code dedupeKey}, for every recipient: the work they announced is done. Their severity becomes
     * {@link Notice.Severity#INFO}, since nothing is left to act on.
     *
     * @return how many notices changed
     */
    int resolve(String dedupeKey, String newTitle);
}
