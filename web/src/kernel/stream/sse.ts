/** What both of the console's event streams share: the server's control frames and how to reconnect (ADR-0052). */

/** The server's keep-alive (`SseHub.PING`). Not a topic — every subscriber gets it. */
export const PING = 'ping';
/** The server is about to close this stream (`SseHub.RECONNECT`): reconnect at once, without backoff. */
export const RECONNECT = 'reconnect';
/** Frames were lost (`SseHub.RESYNC`): the bus came back, or a replay was capped. Refetch the views. */
export const RESYNC = 'resync';

const BACKOFF_FLOOR_MS = 1_000;
const BACKOFF_CAP_MS = 30_000;
/**
 * A connection with no frame for this long is treated as dead. Comfortably above
 * the server's keep-alive interval so one missed beat is not read as death; if
 * `sse.heartbeat-interval` is raised past a third of this, raise this with it.
 */
export const SILENCE_MS = 45_000;
/** Failures past this read as "the server is gone", not "the connection blipped". */
export const OFFLINE_AFTER = 3;

/** Capped exponential backoff with full jitter, so a restart is not stampeded. */
export function backoff(failures: number): number {
  const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_FLOOR_MS * 2 ** (failures - 1));
  return BACKOFF_FLOOR_MS + Math.random() * (ceiling - BACKOFF_FLOOR_MS);
}
