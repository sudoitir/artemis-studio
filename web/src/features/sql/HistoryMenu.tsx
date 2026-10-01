import { Button, Menu, Text } from '@mantine/core';

import type { HistoryEntry } from './queryHistory.ts';

const SOURCE_WORDS: Record<string, string> = { INDEX: 'index' };

/** The queries this browser has run, to load back into the editor — never run on the operator's behalf. */
export function HistoryMenu({
  history,
  opened,
  onOpenChange,
  onLoad,
  onClear,
}: Readonly<{
  history: HistoryEntry[];
  opened: boolean;
  onOpenChange: (opened: boolean) => void;
  onLoad: (sql: string) => void;
  onClear: () => void;
}>) {
  return (
    <Menu shadow="md" width={340} opened={opened} onChange={onOpenChange}>
      <Menu.Target>
        <Button size="xs" variant="default">
          History
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {history.length === 0 ? (
          <Menu.Item disabled>
            <Text size="xs">
              Nothing yet. Queries you run on this browser are remembered here — locally, never sent anywhere.
            </Text>
          </Menu.Item>
        ) : (
          <>
            {history.map((entry) => (
              <Menu.Item key={`${entry.at}-${entry.sql}`} onClick={() => onLoad(entry.sql)}>
                <Text size="xs" lineClamp={2}>
                  {entry.sql}
                </Text>
                <Text size="xs" c="dimmed">
                  {new Date(entry.at).toLocaleString()} · {entry.rowCount.toLocaleString()} row
                  {entry.rowCount === 1 ? '' : 's'}
                  {entry.source ? ` · ${SOURCE_WORDS[entry.source] ?? 'brokers'}` : ''}
                </Text>
              </Menu.Item>
            ))}
            <Menu.Divider />
            <Menu.Item onClick={onClear}>
              <Text size="xs">Clear history</Text>
            </Menu.Item>
          </>
        )}
      </Menu.Dropdown>
    </Menu>
  );
}
