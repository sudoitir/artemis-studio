import { useEffect } from 'react';
import { notifications } from '@mantine/notifications';

import { branding } from '../../branding.ts';
import { bootState } from './boot.ts';

/**
 * Says once, after the page starts, when it started without something it tried to load: the
 * manifest (plugins are then missing too), or a plugin's screens. Studio's own screens are there
 * either way; this is so the gap is stated rather than discovered.
 */
export function BootNotice() {
  useEffect(() => {
    const { manifestError, failures } = bootState();
    if (manifestError) {
      notifications.show({
        id: 'boot-manifest',
        color: 'yellow',
        title: 'Started without plugins',
        message: `The installation manifest could not be read (${manifestError}), so no plugin screens were loaded. Reload to try again.`,
        autoClose: false,
      });
    } else if (failures.size > 0) {
      notifications.show({
        id: 'boot-plugins',
        color: 'yellow',
        title: failures.size === 1 ? 'A plugin could not show its screens' : `${failures.size} plugins could not show their screens`,
        message: `${[...failures.keys()].join(', ')}. The rest of ${branding.productShortName} is unaffected; their addresses explain why.`,
        autoClose: false,
      });
    }
  }, []);
  return null;
}
