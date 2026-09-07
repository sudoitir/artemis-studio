import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const KEY = 'as:display:timezone';

async function fresh() {
  vi.resetModules();
  return {
    tz: await import('./timezone.ts'),
    time: await import('./time.ts'),
  };
}

/**
 * Pretend the browser sits in a zone. Only `resolvedOptions` is intercepted, so
 * real formatting — which is what every assertion below actually checks — still
 * runs through the runtime's own tz data.
 */
function browserIn(zone: string) {
  const real = Intl.DateTimeFormat.prototype.resolvedOptions;
  vi.spyOn(Intl.DateTimeFormat.prototype, 'resolvedOptions').mockImplementation(function (
    this: Intl.DateTimeFormat,
  ) {
    return { ...real.call(this), timeZone: zone };
  });
}

const INSTANT = '2026-09-07T10:15:30.000Z';

describe('display timezone', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('follows the browser when the operator has chosen nothing', async () => {
    browserIn('Asia/Tehran');
    const { tz } = await fresh();

    expect(tz.displayZonePreference()).toBe(tz.AUTO);
    expect(tz.displayZone()).toBe('Asia/Tehran');
  });

  it('keeps following the browser if the machine moves', async () => {
    browserIn('Europe/Berlin');
    const { tz } = await fresh();
    expect(tz.displayZone()).toBe('Europe/Berlin');

    // Automatic has to mean automatic: the zone is resolved per read, not captured.
    vi.restoreAllMocks();
    browserIn('America/New_York');
    expect(tz.displayZone()).toBe('America/New_York');
  });

  it('pins an explicit choice and remembers it across a reload', async () => {
    browserIn('Asia/Tehran');
    const { tz } = await fresh();

    tz.setDisplayZone('UTC');
    expect(tz.displayZone()).toBe('UTC');
    expect(window.localStorage.getItem(KEY)).toBe('UTC');

    // A reload must not quietly hand them back to automatic.
    const again = await fresh();
    expect(again.tz.displayZonePreference()).toBe('UTC');
    expect(again.tz.displayZone()).toBe('UTC');
  });

  it('never discards a stored choice it cannot format', async () => {
    window.localStorage.setItem(KEY, 'Mars/Olympus_Mons');
    const { tz, time } = await fresh();

    // The preference survives — silently reverting it would leave the operator
    // re-picking the same zone forever with nothing saying why.
    expect(tz.displayZonePreference()).toBe('Mars/Olympus_Mons');
    // The label still renders, falling back for that label only.
    expect(time.absoluteLabel(INSTANT)).toBe('2026-09-07 10:15:30Z');
  });

  it('survives storage being unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { tz } = await fresh();

    expect(tz.displayZonePreference()).toBe(tz.AUTO);
    // Unpersisted still applies for this session rather than refusing to change.
    tz.setDisplayZone('UTC');
    expect(tz.displayZone()).toBe('UTC');
  });

  it('offers automatic and UTC first, and names what automatic resolves to', async () => {
    browserIn('Asia/Tehran');
    const { tz } = await fresh();

    const [common] = tz.zoneOptions();
    expect(common.group).toBe('Common');
    expect(common.items[0]).toEqual({ value: 'auto', label: 'Automatic — Asia/Tehran' });
    expect(common.items[1].value).toBe('UTC');
  });
});

describe('absoluteLabel in a chosen zone', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('names the offset it is written in', async () => {
    window.localStorage.setItem(KEY, 'Asia/Tehran');
    const { time } = await fresh();

    // Tehran is UTC+03:30 at this instant.
    expect(time.absoluteLabel(INSTANT)).toBe('2026-09-07 13:45:30 +03:30');
  });

  it('uses the offset in force at that instant, not today', async () => {
    window.localStorage.setItem(KEY, 'Europe/London');
    const { time } = await fresh();

    // Same wall-clock date in summer and in winter, one hour apart.
    expect(time.absoluteLabel('2026-07-01T12:00:00.000Z')).toBe('2026-07-01 13:00:00 +01:00');
    expect(time.absoluteLabel('2026-01-01T12:00:00.000Z')).toBe('2026-01-01 12:00:00 +00:00');
  });

  it('keeps the bare Z form for UTC', async () => {
    window.localStorage.setItem(KEY, 'UTC');
    const { time } = await fresh();

    expect(time.absoluteLabel(INSTANT)).toBe('2026-09-07 10:15:30Z');
  });

  it('still says nothing when there is no timestamp', async () => {
    window.localStorage.setItem(KEY, 'Asia/Tehran');
    const { time } = await fresh();

    expect(time.absoluteLabel(0)).toBe('—');
    expect(time.absoluteLabel(null)).toBe('—');
  });
});
