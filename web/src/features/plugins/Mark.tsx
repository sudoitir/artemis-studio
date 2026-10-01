import { useState } from 'react';

import type { PluginView } from './api.ts';
import styles from './Plugins.module.css';

/** A plugin's icon, or its monogram when it has none or the icon cannot be loaded. */
export function Mark({ plugin }: Readonly<{ plugin: PluginView }>) {
  const [broken, setBroken] = useState(false);
  if (plugin.iconUrl && !broken) {
    // An <img>, never inline SVG: the server also sandboxes the icon (design.md §7).
    return <img src={plugin.iconUrl} alt="" className={styles.icon} onError={() => setBroken(true)} />;
  }
  return (
    <span className={styles.monogram} aria-hidden>
      {plugin.info.title.slice(0, 2).toUpperCase()}
    </span>
  );
}
