/** A viewport point the menu opens from: the pointer, or just under the control that opened it. */
export interface MenuAnchor {
  x: number;
  y: number;
}

/** The anchor just under an element, at its inline start, for a menu opened from the keyboard. */
export function anchorBelow(element: Element): MenuAnchor {
  const rect = element.getBoundingClientRect();
  const rtl = document.dir === 'rtl' || getComputedStyle(document.documentElement).direction === 'rtl';
  return clampToViewport({ x: rtl ? rect.right : rect.left, y: rect.bottom - 1 });
}

/**
 * Keeps an anchor inside the viewport. A point outside it counts as a hidden reference, and the
 * positioning engine then hides the menu outright rather than showing it at the edge.
 */
export function clampToViewport(anchor: MenuAnchor): MenuAnchor {
  return {
    x: Math.min(Math.max(anchor.x, 0), Math.max(window.innerWidth - 1, 0)),
    y: Math.min(Math.max(anchor.y, 0), Math.max(window.innerHeight - 1, 0)),
  };
}
