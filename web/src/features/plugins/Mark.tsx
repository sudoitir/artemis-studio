import { useState } from 'react';

import { safeHref } from '../../ui/safeHref.ts';
import type { PluginView } from './api.ts';
import styles from './Plugins.module.css';

/** A plugin's icon, or its monogram when it has none or the icon cannot be loaded. */
export function Mark({ plugin }: Readonly<{ plugin: PluginView }>) {
  const [broken, setBroken] = useState(false);
  const icon = safeHref(plugin.iconUrl);
  if (icon && !broken) {
    // An <img>, never inline SVG: the server also sandboxes the icon (design.md §7).
    return <img src={icon} alt="" className={styles.icon} onError={() => setBroken(true)} />;
  }
  return (
    <span className={styles.monogram} aria-hidden>
      {plugin.info.title.slice(0, 2).toUpperCase()}
    </span>
  );
}
