import { useCallback, useState } from 'react';

/**
 * A notice the viewer has waved away for the rest of their session.
 *
 * Dismissal is per-viewer, per-session and worth nothing to anyone else, so it
 * lives in `sessionStorage` rather than on the server or in a store
 * (non-negotiable #9). `clearDismissedNotices` runs on sign-out and sign-in, so
 * a notice comes back for the next person to use this browser — "dismissed"
 * must never read as "silenced for good", least of all for someone else.
 *
 * The `key` is expected to encode *what* is being dismissed, not just where it
 * appeared: when the underlying facts change the key changes with them and the
 * notice returns, which is the difference between quieting a known gap and
 * hiding a new one.
 */
const PREFIX = 'as:notice:';

function read(key: string): boolean {
  try {
    return sessionStorage.getItem(PREFIX + key) === 'dismissed';
  } catch {
    // Private mode, or a browser refusing site data. A notice that shows every
    // time is the safe failure.
    return false;
  }
}

export function useDismissedNotice(key: string): [boolean, () => void] {
  const [dismissed, setDismissed] = useState(() => read(key));

  // `key` changes when the facts behind the notice change, so re-derive rather
  // than keeping stale state from the previous key.
  const [seenKey, setSeenKey] = useState(key);
  if (seenKey !== key) {
    setSeenKey(key);
    setDismissed(read(key));
  }

  const dismiss = useCallback(() => {
    try {
      sessionStorage.setItem(PREFIX + key, 'dismissed');
    } catch {
      // Nothing to persist to; the notice simply returns on the next visit.
    }
    setDismissed(true);
  }, [key]);

  return [dismissed, dismiss];
}

/** Forget every dismissal. Called when the signed-in identity changes. */
export function clearDismissedNotices() {
  try {
    for (const k of Object.keys(sessionStorage)) {
      if (k.startsWith(PREFIX)) sessionStorage.removeItem(k);
    }
  } catch {
    // Nothing stored, nothing to clear.
  }
}
