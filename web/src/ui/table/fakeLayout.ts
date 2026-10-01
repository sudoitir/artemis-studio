import { vi } from 'vitest';

/**
 * Test support: jsdom does no layout, so `clientWidth` is 0 everywhere and the table shows every
 * column at its base. This gives the elements the table reads a plausible layout (10 px per `ch`,
 * 16 px of cell padding, 10 px per character of text) so the solver can be exercised. Returns a
 * function that restores jsdom's own.
 */
export function fakeLayout(scrollInlineSize: number): () => void {
  const probes: Record<string, number> = { ch: 1000, mono: 1000, pad: 16, select: 40, actions: 44 };
  const spy = vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
    if (this.dataset.probe) return probes[this.dataset.probe];
    const grid = this.parentElement;
    if (grid?.style.gridTemplateColumns.startsWith('repeat(')) {
      // A cell of the measurer's `max-content` grid: its track is as wide as the widest cell of its column.
      const tracks = Number(/repeat\((\d+)/.exec(grid.style.gridTemplateColumns)?.[1]);
      const column = [...grid.children].indexOf(this) % tracks;
      const widths = [...grid.children]
        .filter((_, i) => i % tracks === column)
        .map((cell) => ((cell as HTMLElement).dataset.text?.length ?? 0) * 10 + probes.pad);
      return Math.max(...widths);
    }
    return this.querySelector(':scope > [role="grid"], :scope > table') ? scrollInlineSize : 0;
  });
  return () => spy.mockRestore();
}
