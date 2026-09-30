const MAX_LENGTH = 40;

/**
 * A user agent as a person would say it: "Firefox on Linux". A string that is not a browser (a
 * script, a tool) is shown as its first product token, and a missing one as unknown, so a row is
 * never blank. Only what the string states is used; nothing here is a claim about the device.
 */
export function describeClient(userAgent: string | null | undefined): string {
  const ua = userAgent?.trim();
  if (!ua) return 'Unknown client';
  const browser = describeBrowser(ua);
  if (browser) return browser;
  const token = ua.split(/\s/)[0] ?? ua;
  return token.length > MAX_LENGTH ? `${token.slice(0, MAX_LENGTH)}…` : token;
}

/** "Firefox on Linux", or null when the string is not a browser's: for a name that must not be a guess. */
export function describeBrowser(userAgent: string): string | null {
  const browser = browserOf(userAgent);
  const os = osOf(userAgent);
  return browser && os ? `${browser} on ${os}` : browser;
}

// Order matters: Edge and Opera also say Chrome, and Chrome also says Safari.
function browserOf(ua: string): string | null {
  if (/Edg(e|A|iOS)?\//.test(ua)) return 'Edge';
  if (/OPR\/|Opera/.test(ua)) return 'Opera';
  if (/Firefox\/|FxiOS\//.test(ua)) return 'Firefox';
  if (/Chrome\/|CriOS\//.test(ua)) return 'Chrome';
  if (/Safari\//.test(ua) && /Version\//.test(ua)) return 'Safari';
  return null;
}

// Order matters: iOS and Android also say Mac OS X and Linux.
function osOf(ua: string): string | null {
  if (/iPhone|iPad|iPod/.test(ua)) return 'iOS';
  if (/Android/.test(ua)) return 'Android';
  if (/Windows/.test(ua)) return 'Windows';
  if (/CrOS/.test(ua)) return 'ChromeOS';
  if (/Macintosh|Mac OS X/.test(ua)) return 'macOS';
  if (/Linux|X11/.test(ua)) return 'Linux';
  return null;
}
