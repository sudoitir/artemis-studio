import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { MiddleTruncate } from '../../ui/table/MiddleTruncate.tsx';
import classes from './Configuration.module.css';
import { Transition } from './Transition.tsx';

function one(value: unknown): string {
  if (value === undefined) return '—';
  if (Array.isArray(value)) return value.length === 0 ? '—' : value.join(',');
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

/**
 * One step as a diff: a row per key, `before → after`, with the keys that do not
 * change dimmed underneath rather than hidden.
 *
 * Two columns of `k=v · k=v` made the reader do the comparison — on an address
 * setting carrying eighteen keys of which one moves, the one that moves is not
 * findable. The unchanged keys stay because a management write **replaces** the
 * whole entry (notes §15 M2): they are not context, they are part of what is
 * being written.
 */
export function StepDiff({
  before,
  after,
}: Readonly<{ before: Record<string, unknown>; after: Record<string, unknown> }>) {
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].sort((a, b) => a.localeCompare(b));
  if (keys.length === 0) return <>—</>;
  // Nothing is there yet, so every key would read `— → value`. The step's own
  // description already says it creates the thing; what is worth reading is what
  // it will be created as.
  if (Object.keys(before).length === 0) {
    return <DescriptionList oneLine items={keys.map((key) => ({ term: key, value: one(after[key]) }))} />;
  }
  const rows = keys.map((key) => ({
    key,
    before: one(before[key]),
    after: one(after[key]),
    differs: one(before[key]) !== one(after[key]),
  }));
  const ordered = [...rows.filter((r) => r.differs), ...rows.filter((r) => !r.differs)];
  return (
    <DescriptionList
      oneLine
      items={ordered.map((r) => ({
        term: r.key,
        value: r.differs ? (
          <Transition before={r.before} after={r.after} />
        ) : (
          <span className={`${classes.before} ${classes.side}`}>
            <MiddleTruncate text={r.after} tooltip />
          </span>
        ),
      }))}
    />
  );
}
