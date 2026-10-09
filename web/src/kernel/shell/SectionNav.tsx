import { useEffect, useRef, type ReactNode } from 'react';
import { useSearch } from '@tanstack/react-router';

import { NavGroup } from './NavGroup.tsx';
import { NavItem } from './NavItem.tsx';
import classes from './SectionNav.module.css';

export interface SectionTab {
  id: string;
  title: string;
  panel: ReactNode;
  /** Shown at the end of the row, such as a count or a marker; its words become part of the link's name. */
  aside?: ReactNode;
  /** Loads what the section needs; called when the pointer or focus reaches its link. */
  preload?: () => void;
}

export interface SectionGroup {
  id: string;
  label: string;
  tabs: SectionTab[];
}

/**
 * A page's sections as a list of links under fixed group headings, the open one beside the list. It is
 * navigation, not an ARIA tab widget: every row is a link to the same page with `?tab=<id>`, the open one
 * is `aria-current="page"`, and the open section is in the address, so it can be shared and survives a
 * reload. Any other value opens the first section. A group with no sections is left out.
 *
 * Choosing a section never moves the page: the link does not reset the scroll, and the list scrolls the
 * page only when the top of the new section is above the window. A keyboard user who activates a link
 * lands in the section, which is named, so they read what they chose rather than staying in the list.
 * The list is its own scroll area when it is taller than the window, its group headings sticky.
 *
 * @param keep the search values that survive a change of section (a filter that applies to every one);
 *   the rest belong to the section that was open and are dropped.
 */
export function SectionNav({
  label,
  groups,
  keep = [],
}: Readonly<{ label: string; groups: SectionGroup[]; keep?: string[] }>) {
  const search = useSearch({ strict: false }) as Record<string, unknown>;
  const panel = useRef<HTMLDivElement>(null);
  const byKeyboard = useRef(false);
  const shown = groups.filter((group) => group.tabs.length > 0);
  const ordered = shown.flatMap((group) => group.tabs);
  const open = ordered.find((candidate) => candidate.id === search.tab) ?? ordered[0];
  const openId = open?.id;

  const kept = Object.fromEntries(keep.filter((key) => search[key] !== undefined).map((key) => [key, search[key]]));

  const firstRender = useRef(true);
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    const element = panel.current;
    if (!element) return;
    if (byKeyboard.current) {
      byKeyboard.current = false;
      element.focus({ preventScroll: true });
    }
    // Only when the section's top is out of sight does the page move; otherwise it stays where it is.
    const top = element.getBoundingClientRect().top;
    const header = Number.parseFloat(getComputedStyle(element).scrollMarginBlockStart) || 0;
    if (top < header) element.scrollIntoView({ block: 'start', behavior: 'instant' });
  }, [openId]);

  return (
    <div className={classes.frame}>
      <div className={classes.root}>
        <nav
          aria-label={label}
          className={classes.list}
          onKeyDown={(event) => {
            if (event.key === 'Enter') byKeyboard.current = true;
          }}
        >
          {shown.map((group) => (
            <NavGroup key={group.id} label={group.label} sticky>
              {group.tabs.map(({ id, title, aside, preload }) => (
                <NavItem
                  key={id}
                  to="."
                  search={{ ...kept, tab: id }}
                  current={id === openId}
                  label={title}
                  trailing={aside}
                  onIntent={preload}
                />
              ))}
            </NavGroup>
          ))}
        </nav>
        {open ? (
          <div
            key={open.id}
            ref={panel}
            role="group"
            aria-label={open.title}
            data-section-panel
            tabIndex={-1}
            className={classes.panel}
          >
            {open.panel}
          </div>
        ) : null}
      </div>
    </div>
  );
}
