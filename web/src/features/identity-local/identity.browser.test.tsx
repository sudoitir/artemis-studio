import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { axeViolations, renderThemed, SCHEMES } from '../../test/browser.tsx';
import { QrCode } from './QrCode.tsx';
import { RecoveryCodesDialog } from './RecoveryCodesDialog.tsx';

/**
 * The one-time recovery codes and the setup QR code as Chromium lays them out, in both schemes: the
 * codes sit in a grid that wraps by width, and the QR code is an image with a name.
 */

const CODES = [
  'ABCDEFGHJK',
  'LMNPQRSTUV',
  'WXYZ234567',
  'ABCDE23456',
  'FGHJK78923',
  'LMNPQ45678',
  'RSTUV34567',
  'WXYZA23456',
  'BCDEF67892',
  'GHJKL34567',
];

describe.each(SCHEMES)('the recovery codes dialog in the %s scheme', (scheme) => {
  it('lists every code inside its box and has no accessibility violations', async () => {
    renderThemed(<RecoveryCodesDialog codes={CODES} onContinue={() => {}} />, scheme);

    const list = await screen.findByRole('list', { name: 'Recovery codes' });
    expect(list.scrollWidth).toBeLessThanOrEqual(list.clientWidth);
    expect(await axeViolations(document.body)).toEqual([]);
  });
});

describe.each(SCHEMES)('the setup QR code in the %s scheme', (scheme) => {
  it('is a named image of one fixed size, with no accessibility violations', async () => {
    const { container } = renderThemed(
      <QrCode value="otpauth://totp/Studio:alice?secret=JBSWY3DPEHPK3PXP" label="QR code for your authenticator app" />,
      scheme,
    );

    const image = await screen.findByRole('img', { name: 'QR code for your authenticator app' });
    const box = image.getBoundingClientRect();
    expect(box.width).toBe(box.height);
    expect(await axeViolations(container)).toEqual([]);
  });
});
