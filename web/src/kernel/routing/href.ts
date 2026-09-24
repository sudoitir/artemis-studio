import { defaultStringifySearch } from '@tanstack/react-router';

/**
 * The address of a view of one cluster, serialized the way the router serializes it, so a link built
 * here and a link the router builds for the same state are the same string. For anchors (so a link
 * can be opened in a new tab) and for "Copy link".
 */
export function clusterHref(clusterId: string, path: string, search: Record<string, unknown> = {}): string {
  const defined = Object.fromEntries(Object.entries(search).filter(([, v]) => v !== undefined && v !== ''));
  return `/clusters/${encodeURIComponent(clusterId)}/${path.replace(/^\/+/, '')}${defaultStringifySearch(defined)}`;
}

/** An in-app address as a full URL, for sharing. */
export function absoluteHref(href: string): string {
  return new URL(href, window.location.origin).href;
}
