/** A server instant in the viewer's locale, or `fallback` when there is none. */
export function formatInstant(iso: string | null | undefined, fallback = 'never'): string {
  return iso ? new Date(iso).toLocaleString() : fallback;
}
