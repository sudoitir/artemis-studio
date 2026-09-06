## 1. Decision record

- [x] 1.1 `docs/adr/0052-freshness-indicator-and-stream-reconnection.md` — one
      global indicator scoped to observed queries; the stream reconnects forever
      with jittered backoff and a silence watchdog; the keep-alive becomes an
      observable event. References ADR-0003, ADR-0018, ADR-0027.
- [x] 1.2 `docs/adr/README.md` index row.

## 2. The stream stops giving up

- [x] 2.1 `sse/SseHub.heartbeat` sends a named `ping` event instead of a comment.
      Keep it payload-free and keep the drop-on-failure behaviour.
- [x] 2.2 `SseHubTest` — an idle subscriber receives a named event, not only a
      comment.
- [x] 2.3 `web/src/api/stream.ts` — `useClusterStream` returns
      `StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'offline'` instead
      of `void`.
- [x] 2.4 Reconnect indefinitely: capped exponential backoff with full jitter,
      1s floor, 30s cap. Remove the two-failure surrender.
- [x] 2.5 Silence watchdog: any frame (including `ping`) resets a timer; expiry
      closes the source and reconnects. Window is comfortably above the server's
      heartbeat interval.
- [x] 2.6 `stream.test.tsx` — with `EventSourceStub`, the client retries past the
      third failure; a silent stream is reconnected; a recovered stream reports
      `live`.

## 3. Freshness state

- [x] 3.1 `web/src/app/useFreshness.ts` — subscribe to the query cache; derive
      `lastUpdatedAt` (newest `dataUpdatedAt` among queries with active
      observers), `isFetching`, `hasError`. Tick once a second only to re-render
      the relative label.
- [x] 3.2 `web/src/app/StreamStatusContext.ts` — provided by `ClusterLayout` from
      `useClusterStream`'s return; null default so non-cluster routes render.
- [x] 3.3 `web/src/api/client.ts` — module-level pause signal, `poll(ms)` helper,
      `setPolling(paused)`, `usePollingPaused()`. Apply `poll(…)` at the fifteen
      existing `refetchInterval` sites; no cadence changes.
- [x] 3.4 Stream invalidations pass `refetchType: 'none'` while paused, and record
      that data changed, so the bar can say new data is waiting.

## 4. The control

- [x] 4.1 `web/src/theme.css` — `--as-live`, `--as-stale`, `--as-offline`. No
      green; map onto existing text/warning tokens.
- [x] 4.2 `web/src/app/FreshnessBar.tsx` + `.module.css` — state dot and label,
      `Updated Ns ago` as a `<time dateTime>` with the absolute time in `title`,
      refresh `ActionIcon` (`aria-label="Refresh data"`, spinning while
      fetching), pause toggle. `aria-live="polite"` region announces state
      transitions only.
- [x] 4.3 Mount in `app/RootLayout.tsx`'s header right group, before `UserMenu`.
- [x] 4.4 `app/ClusterLayout.tsx` provides the stream status; drop the stale
      "falls back to the 5s poll on two failures" comment.
- [x] 4.5 `palette/CommandPalette.tsx` — register "Refresh data" and
      "Pause/Resume auto-refresh". No hotkey.

## 5. Tests

- [x] 5.1 `FreshnessBar.test.tsx` — the five labels; refresh invalidates active
      queries; pause suppresses polling and reports waiting data; the elapsed
      label is not announced.
- [x] 5.2 A non-polling screen reports a growing age rather than nothing.

## 6. Close-out

- [x] 6.1 `openspec/specs/` deltas merged on archive: new `data-freshness`,
      modified `realtime-stream`.
- [x] 6.2 Commit message — `feat(ui):`, body written for someone upgrading
      (`.claude/rules/05-commits.md`).
- [x] 6.3 `just verify` green.
