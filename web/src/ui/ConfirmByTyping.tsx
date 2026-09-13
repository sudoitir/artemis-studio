import { useState } from 'react';
import { Button, Stack, TextInput } from '@mantine/core';

/**
 * Typed-name confirmation for a destructive action (non-negotiable #2). The
 * button arms only on an exact match of {@code token}. Extracted from the
 * hand-rolled copies in `AddManagementUrl` (remove cluster) and `SettingsView`
 * (credential rotation).
 */
export function ConfirmByTyping({
  token,
  label,
  confirmLabel,
  loading,
  disabled,
  color = 'red',
  onConfirm,
}: {
  token: string;
  label?: string;
  confirmLabel: string;
  loading?: boolean;
  disabled?: boolean;
  color?: string;
  onConfirm: () => void;
}) {
  const [typed, setTyped] = useState('');
  const armed = typed === token && !disabled;

  return (
    <Stack gap="xs">
      <TextInput
        label={label ?? `Type "${token}" to confirm`}
        value={typed}
        onChange={(e) => setTyped(e.currentTarget.value)}
        size="xs"
        autoComplete="off"
      />
      <Button size="xs" color={color} disabled={!armed} loading={loading} onClick={onConfirm}>
        {confirmLabel}
      </Button>
    </Stack>
  );
}
