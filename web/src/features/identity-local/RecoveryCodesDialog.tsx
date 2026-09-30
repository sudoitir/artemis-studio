import { useEffect, useState } from 'react';
import { Button, Checkbox, CopyButton, Group, Modal, SimpleGrid, Stack, Text, VisuallyHidden } from '@mantine/core';
import { IconCheck, IconCopy, IconDownload } from '@tabler/icons-react';

import { branding } from '../../branding.ts';

/** How a code is shown and saved: two groups of five, the way it is typed back. */
const grouped = (code: string) => (code.includes('-') ? code : code.replace(/^(.{5})(?=.)/, '$1-'));

const filename = () => `${branding.productName.toLowerCase().replace(/\s+/g, '-')}-recovery-codes.txt`;

function fileText(codes: string[]): string {
  return [
    `${branding.productName} recovery codes`,
    '',
    'Each code signs you in once, if you lose your authenticator app or passkey.',
    'Keep this file somewhere safe. Anyone who has a code and your password can sign in as you.',
    '',
    ...codes.map(grouped),
    '',
  ].join('\n');
}

function download(codes: string[]) {
  const url = URL.createObjectURL(new Blob([fileText(codes)], { type: 'text/plain' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename();
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * The recovery codes, shown once (ADR-0143). Nothing about this dialog is easy to dismiss by accident: the codes
 * cannot be shown again, so Escape, a click outside and a close button are all absent, and the way out is
 * the acknowledgement that they were saved. The button stays enabled and says what is missing, rather than
 * being disabled without a reason.
 */
export function RecoveryCodesDialog({
  codes,
  onContinue,
}: Readonly<{
  /** The codes to show, or null while there are none to show. */
  codes: string[] | null;
  onContinue: () => void;
}>) {
  const [saved, setSaved] = useState(false);
  const [missing, setMissing] = useState(false);
  const [downloaded, setDownloaded] = useState(false);

  useEffect(() => {
    if (codes === null) {
      setSaved(false);
      setMissing(false);
      setDownloaded(false);
    }
  }, [codes]);

  const text = codes ? codes.map(grouped).join('\n') : '';

  return (
    <Modal
      opened={codes !== null}
      onClose={() => {}}
      title="Save your recovery codes"
      closeOnEscape={false}
      closeOnClickOutside={false}
      withCloseButton={false}
      size="md"
    >
      {codes ? (
        <Stack gap="md">
          <Text size="sm">
            If you lose your authenticator app or passkey, each of these codes signs you in once. They are shown only
            now, and cannot be shown again.
          </Text>

          <SimpleGrid
            component="ul"
            cols={2}
            spacing="xs"
            aria-label="Recovery codes"
            p="sm"
            m={0}
            style={{
              listStyle: 'none',
              border: '1px solid var(--as-border)',
              borderRadius: 'var(--mantine-radius-md)',
              background: 'var(--as-surface-raised)',
            }}
          >
            {codes.map((code) => (
              <Text key={code} component="li" ff="monospace" size="sm" style={{ fontVariantNumeric: 'tabular-nums' }}>
                {grouped(code)}
              </Text>
            ))}
          </SimpleGrid>

          <Group gap="xs">
            <CopyButton value={text}>
              {({ copied, copy }) => (
                <>
                  <Button
                    variant="default"
                    size="xs"
                    leftSection={copied ? <IconCheck size={14} aria-hidden /> : <IconCopy size={14} aria-hidden />}
                    onClick={copy}
                  >
                    {copied ? 'Copied' : 'Copy all'}
                  </Button>
                  <VisuallyHidden aria-live="polite">
                    {copied ? 'Recovery codes copied to the clipboard.' : ''}
                  </VisuallyHidden>
                </>
              )}
            </CopyButton>
            <Button
              variant="default"
              size="xs"
              leftSection={<IconDownload size={14} aria-hidden />}
              onClick={() => {
                download(codes);
                setDownloaded(true);
              }}
            >
              Download
            </Button>
          </Group>
          <VisuallyHidden aria-live="polite">{downloaded ? `Saved as ${filename()}.` : ''}</VisuallyHidden>

          <Text size="xs" c="dimmed">
            This window stays open until you confirm below. Escape does not close it, so the codes are not lost by a
            stray key press.
          </Text>

          <Checkbox
            label="I saved these codes somewhere safe"
            checked={saved}
            onChange={(e) => {
              setSaved(e.currentTarget.checked);
              setMissing(false);
            }}
            error={missing ? 'Confirm you saved the codes before continuing.' : undefined}
          />

          <Group justify="flex-end">
            <Button
              onClick={() => {
                if (!saved) {
                  setMissing(true);
                  return;
                }
                onContinue();
              }}
            >
              Continue
            </Button>
          </Group>
        </Stack>
      ) : null}
    </Modal>
  );
}
