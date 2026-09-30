import { useMemo } from 'react';
import { encode } from 'uqr';

/** The quiet zone the QR specification asks for, in modules; a scanner needs it to find the code's edge. */
const QUIET_ZONE = 4;

/**
 * A QR code as an inline SVG. It is drawn dark on light in both colour schemes — scanners read dark modules on a
 * light field, and an inverted code fails on many of them — so the light quiet zone is part of the picture and
 * stays light on the dark theme. `label` names it for a screen reader; the same secret is offered as text beside it.
 */
export function QrCode({ value, label, size = 176 }: Readonly<{ value: string; label: string; size?: number }>) {
  const { path, modules } = useMemo(() => {
    const { data, size: modules } = encode(value, { ecc: 'M', border: QUIET_ZONE });
    // One path, a run of dark modules per row, so the markup stays small and the edges stay sharp.
    let path = '';
    data.forEach((row, y) => {
      let x = 0;
      while (x < row.length) {
        if (!row[x]) {
          x += 1;
          continue;
        }
        const start = x;
        while (x < row.length && row[x]) x += 1;
        path += `M${start} ${y}h${x - start}v1h-${x - start}z`;
      }
    });
    return { path, modules };
  }, [value]);

  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`0 0 ${modules} ${modules}`}
      shapeRendering="crispEdges"
      style={{ display: 'block', borderRadius: 'var(--mantine-radius-sm)', outline: '1px solid var(--as-border)' }}
    >
      <rect width={modules} height={modules} fill="var(--mantine-color-white)" />
      <path d={path} fill="var(--mantine-color-black)" />
    </svg>
  );
}
