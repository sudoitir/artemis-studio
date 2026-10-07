import { useId } from 'react';
import { SegmentedControl, Stack, Text } from '@mantine/core';

/** A choice among a few kinds, with a visible label like every other field's. */
export function KindField<V extends string>({
  label,
  data,
  value,
  onChange,
  disabled,
}: Readonly<{
  label: string;
  data: { value: V; label: string; disabled?: boolean }[];
  value: V;
  onChange: (next: V) => void;
  disabled?: boolean;
}>) {
  const id = useId();
  return (
    <Stack gap={4} align="flex-start">
      <Text size="sm" fw={500} id={id}>
        {label}
      </Text>
      <SegmentedControl
        aria-labelledby={id}
        data={data}
        value={value}
        onChange={(next) => onChange(next as V)}
        disabled={disabled}
      />
    </Stack>
  );
}
