import { Text } from '@mantine/core';

const BYTES_PER_ROW = 16;

/**
 * Hex + ASCII dump of a binary body's leading bytes.
 *
 * A binary body is never `TextDecoder`ed into the code block: that renders as
 * mojibake, which an operator reads as corruption in their payload rather than as
 * Studio decoding bytes that were never text.
 */
export function HexDump({ bytes, max = 512 }: { bytes: Uint8Array; max?: number }) {
  const shown = bytes.subarray(0, max);
  const rows: string[] = [];
  for (let offset = 0; offset < shown.length; offset += BYTES_PER_ROW) {
    const row = shown.subarray(offset, offset + BYTES_PER_ROW);
    const hex = Array.from(row)
      .map((b) => b.toString(16).padStart(2, '0'))
      .join(' ')
      .padEnd(BYTES_PER_ROW * 3 - 1, ' ');
    const ascii = Array.from(row)
      .map((b) => (b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '.'))
      .join('');
    rows.push(`${offset.toString(16).padStart(8, '0')}  ${hex}  |${ascii}|`);
  }

  return (
    <>
      <Text
        component="pre"
        size="xs"
        ff="monospace"
        style={{ margin: 0, overflowX: 'auto', whiteSpace: 'pre' }}
      >
        {rows.join('\n')}
      </Text>
      {bytes.length > max ? (
        <Text size="xs" c="dimmed">
          First {max} bytes shown.
        </Text>
      ) : null}
    </>
  );
}
