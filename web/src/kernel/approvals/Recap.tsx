import { Text } from '@mantine/core';

import { DescriptionList, type DescriptionItem } from '../../ui/DescriptionList.tsx';
import type { HeldOperationDetail } from './api.ts';
import { namesTargets, rowText } from './columns.tsx';
import classes from './Approvals.module.css';
import { effectLabel } from './words.ts';

/** How many change rows the recap repeats before it points back to the page for the rest. */
const RECAP_ROWS = 5;

/**
 * What is being decided, said once more where the decision is confirmed or a request cancelled: the summary,
 * the estimated effect, the first changes and the reason, so the operator confirms what they just read.
 */
export function Recap({ detail, reason }: Readonly<{ detail: HeldOperationDetail; reason?: string | null }>) {
  const { effect, display } = detail;
  const targets = namesTargets(display);
  const items: DescriptionItem[] = [
    {
      term: 'Effect',
      value: effect ? effectLabel(effect) : 'Unavailable: Studio could not estimate it',
      hint: effect?.detail ?? undefined,
    },
    ...display.slice(0, RECAP_ROWS).map((row) => ({
      term: row.label,
      value: rowText(row, targets),
    })),
  ];
  if (reason !== undefined) items.push({ term: 'Your reason', value: reason || 'None given' });
  return (
    <div className={classes.recap}>
      <Text size="sm" fw={600} mb="xs">
        {detail.operation.summary}
      </Text>
      <DescriptionList items={items} label="The request" />
      {display.length > RECAP_ROWS ? (
        <Text size="xs" c="dimmed" mt="xs">
          And {display.length - RECAP_ROWS} more changes, listed on the page.
        </Text>
      ) : null}
    </div>
  );
}
