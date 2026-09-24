import { useEffect } from 'react';
import { Button, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import type { ManifestView } from '../manifest.ts';
import { bootState } from './boot.ts';

const NOTIFICATION_ID = 'plugins-changed';
const CHECK_EVERY_MS = 60_000;

/**
 * Offers a reload once the server's plugins differ from the ones this page started with (ADR-0100):
 * checked when the window regains focus and every minute. It never reloads on its own — the
 * operator may be halfway through something — and says so once, until they act.
 */
export function usePluginsChanged(onChange: () => void = showReloadNotice): void {
  useEffect(() => {
    const started = bootState().manifest?.version;
    if (started === undefined) return;
    let told = false;
    const check = async () => {
      if (told) return;
      try {
        const response = await fetch('/api/v1/manifest', { credentials: 'same-origin' });
        if (!response.ok) return;
        const now = (await response.json()) as ManifestView;
        if (now.version !== started) {
          told = true;
          onChange();
        }
      } catch {
        // Offline for a moment; the next check tries again.
      }
    };
    const onFocus = () => void check();
    window.addEventListener('focus', onFocus);
    const timer = window.setInterval(() => void check(), CHECK_EVERY_MS);
    return () => {
      window.removeEventListener('focus', onFocus);
      window.clearInterval(timer);
    };
  }, [onChange]);
}

function showReloadNotice() {
  notifications.show({
    id: NOTIFICATION_ID,
    title: 'Plugins changed',
    message: (
      <Stack gap="xs" align="flex-start">
        <Text size="sm">A plugin was installed, updated or removed. Reload to see the change.</Text>
        <Button size="xs" variant="default" onClick={() => window.location.reload()}>
          Reload
        </Button>
      </Stack>
    ),
    autoClose: false,
    withCloseButton: true,
  });
}
