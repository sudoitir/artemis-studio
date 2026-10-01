import { violationsOf } from './api.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { Notice } from '../../ui/Notice.tsx';
import styles from './Plugins.module.css';

/**
 * Why the server refused a plugin action, with what to do about it. The plugin endpoints list their
 * reasons as violations, each with the fix; any other failure reads as an `ErrorState`.
 */
export function Refusal({ error, title = 'Not done' }: Readonly<{ error: unknown; title?: string }>) {
  const violations = violationsOf(error);
  if (violations.length === 0) return <ErrorState variant="inline" error={error} />;
  return (
    <Notice title={title} tone="danger">
      <ul className={styles.violations}>
        {violations.map((v) => (
          <li key={v.code + v.message}>
            {v.message}
            {v.fix ? <span className={styles.note}> Next: {v.fix}</span> : null}
          </li>
        ))}
      </ul>
    </Notice>
  );
}
