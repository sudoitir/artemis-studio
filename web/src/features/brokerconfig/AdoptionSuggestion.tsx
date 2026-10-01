import { useEffect } from 'react';
import { Button, Group, List, Popover, Stack, Text } from '@mantine/core';

import { useAdoptBrokerConfig, type ConfigDeclarationView } from './api.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { WHY_NOT_AUTOMATIC } from './words.ts';
import classes from './Configuration.module.css';
import { CountsList } from './XmlDrawers.tsx';

/**
 * The first thing an operator sees on a cluster with live nodes and no
 * declaration: what adopting would declare, in counts, before they open anything.
 *
 * The empty state below this one already teaches what a declaration is. What it
 * could not do is answer "how much is there?" — and an operator will not press a
 * button labelled "Adopt from cluster" without knowing whether it means four
 * entries or four hundred. The preview is read-only and costs one batched read
 * per node, the same read the drift pass makes.
 *
 * It suggests. It never adopts: the button opens the ordinary preview drawer,
 * where the counts are shown again beside the confirmation.
 */
export function AdoptionSuggestion({
  declaration,
  onAdopt,
  canWrite,
  blockedReason,
}: Readonly<{
  declaration: ConfigDeclarationView;
  onAdopt: () => void;
  canWrite: boolean;
  blockedReason?: string;
}>) {
  const adopt = useAdoptBrokerConfig(declaration.clusterId);
  const { mutate: read } = adopt;
  const live = declaration.nodes.filter((n) => n.live);

  // One read on arrival. Re-reading on every render would poll the brokers.
  useEffect(() => {
    if (live.length > 0) read();
  }, [declaration.clusterId, live.length, read]);

  if (live.length === 0) return null;

  const result = adopt.data;
  const total = result
    ? result.document.addresses.length +
      result.document.addressSettings.length +
      result.document.securitySettings.length +
      result.document.diverts.length
    : 0;

  return (
    <Section variant="card" title="Adopt what this cluster runs as revision 1">
      <Stack gap="sm">
        <Text size="sm">
          {live.length} live node{live.length === 1 ? '' : 's'} can be read. Adopting declares what they run today, so
          the first revision starts in sync and every later change shows as a difference from it. Nothing is written to
          a broker.
        </Text>

        {/* The result's room is held while the nodes are read, so what is below the card does not move. */}
        <div aria-live="polite" className={classes.reading}>
          {adopt.isPending ? <LoadingState label="Reading the live nodes" blockSize="10rem" /> : null}
          {adopt.isError ? (
            <Stack gap="xs">
              <ErrorState variant="inline" error={adopt.error} onRetry={() => read()} />
              <Text size="sm">That is not the same as there being nothing to adopt.</Text>
            </Stack>
          ) : null}
          {result ? (
            <Stack gap="xs">
              <Text size="sm" fw={600}>
                {total} entr{total === 1 ? 'y' : 'ies'} would be declared
              </Text>
              <CountsList current={declaration.document} next={result.document} />
              {result.disagreements.length > 0 ? (
                <>
                  <Text size="sm" fw={600}>
                    The nodes disagree on {result.disagreements.length} item
                    {result.disagreements.length === 1 ? '' : 's'}
                  </Text>
                  <List size="sm" spacing="xs">
                    {result.disagreements.map((d) => (
                      <List.Item key={d}>{d}</List.Item>
                    ))}
                  </List>
                </>
              ) : null}
            </Stack>
          ) : null}
        </div>

        <Group gap="sm">
          <Button size="xs" onClick={onAdopt} disabled={!canWrite}>
            Review and adopt as revision 1
          </Button>
          <Popover width="22.5rem" position="bottom-start" withArrow shadow="md">
            <Popover.Target>
              <Button variant="subtle" size="compact-sm">
                Why does Studio not do this for me?
              </Button>
            </Popover.Target>
            <Popover.Dropdown>
              <Text size="sm">{WHY_NOT_AUTOMATIC}</Text>
            </Popover.Dropdown>
          </Popover>
        </Group>
        {canWrite || !blockedReason ? null : (
          <Text size="sm" c="dimmed">
            {blockedReason}
          </Text>
        )}
      </Stack>
    </Section>
  );
}
