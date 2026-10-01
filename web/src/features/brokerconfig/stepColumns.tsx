import { Text } from '@mantine/core';

import type { ConfigApplyOutcomeView, ConfigStepApplyView } from './api.ts';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import type { Column } from '../../ui/table/index.ts';
import { StepDiff } from './stepCells.tsx';
import { stepStatusWords, valueWords, wireSectionLabel } from './words.ts';

type PlannedStep = ConfigApplyOutcomeView['plan']['nodes'][number]['steps'][number];

/** One step of a node's plan or result: what happened, the plan's before and after, and its place in the plan. */
export interface StepRow {
  step: ConfigStepApplyView;
  /** The step's place in the whole plan, not in the filtered view: the number a halt message refers to. */
  number: number;
  plan: PlannedStep | undefined;
}

/** The columns of a node's steps. The step's number identifies a row; its status is a word, never a colour alone. */
export function stepColumns(): Column<StepRow>[] {
  return [
    {
      id: 'number',
      header: '#',
      description: "The step's place in the plan",
      accessor: (r) => r.number,
      kind: 'number',
      priority: 'essential',
    },
    {
      id: 'step',
      header: 'Step',
      accessor: (r) => `${r.step.description} ${r.step.op.toLowerCase()} ${r.step.key}`,
      cell: ({ step }) => (
        <>
          <div>{step.description}</div>
          <Text size="sm" c="dimmed">
            {step.op.toLowerCase()} {wireSectionLabel(step.section)} {step.key}
          </Text>
        </>
      ),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'change',
      header: 'Change',
      accessor: (r) =>
        r.plan
          ? Object.entries(r.plan.after)
              .map(([key, value]) => `${key} ${valueWords(value)}`)
              .join(' ')
          : '—',
      cell: ({ plan }) => (plan ? <StepDiff before={plan.before} after={plan.after} /> : '—'),
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'status',
      header: 'Status',
      accessor: (r) => stepStatusWords(r.step).text,
      cell: ({ step }) => {
        const words = stepStatusWords(step);
        return (
          <>
            <StatusBadge tone={words.tone ?? 'neutral'}>{words.text}</StatusBadge>
            {step.error ? (
              <Text size="sm" c="dimmed">
                {step.error}
              </Text>
            ) : null}
          </>
        );
      },
      kind: 'text',
      badge: true,
      priority: 'essential',
      wrap: true,
    },
  ];
}
