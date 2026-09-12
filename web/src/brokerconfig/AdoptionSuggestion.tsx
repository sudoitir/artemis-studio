import { useEffect } from 'react';
import { Alert, Anchor, Button, Group, List, Stack, Text, Tooltip } from '@mantine/core';

import { useAdoptBrokerConfig, type ConfigDeclarationView } from '../api/client.ts';
import { WHY_NOT_AUTOMATIC } from './words.ts';
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
}: {
  declaration: ConfigDeclarationView;
  onAdopt: () => void;
  canWrite: boolean;
  blockedReason?: string;
}) {
  const adopt = useAdoptBrokerConfig(declaration.clusterId);
  const live = declaration.nodes.filter((n) => n.live);

  useEffect(() => {
    if (live.length > 0) adopt.mutate();
    // One read on arrival. Re-reading on every render would poll the brokers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [declaration.clusterId, live.length]);

  if (live.length === 0) return null;

  const result = adopt.data;
  const total = result
    ? result.document.addresses.length +
      result.document.addressSettings.length +
      result.document.securitySettings.length +
      result.document.diverts.length
    : 0;

  return (
    <Alert variant="light" color="gray" title="Adopt what this cluster runs as revision 1">
      <Stack gap="sm">
        <Text size="sm">
          {live.length} live node{live.length === 1 ? '' : 's'} can be read. Adopting declares what they run today, so
          the first revision starts in sync and every later change shows as a difference from it. Nothing is written
          to a broker.
        </Text>

        <div aria-live="polite">
          {adopt.isPending ? <Text size="sm">Reading the live nodes…</Text> : null}
          {adopt.isError ? (
            <Text size="sm">
              The nodes could not be read: {adopt.error.message} That is not the same as there being nothing to adopt.
            </Text>
          ) : null}
          {result ? (
            <Stack gap={4}>
              <Text size="sm" fw={600}>
                {total} entr{total === 1 ? 'y' : 'ies'} would be declared
              </Text>
              <CountsList current={declaration.document} next={result.document} />
              {result.disagreements.length > 0 ? (
                <>
                  <Text size="xs" fw={600} mt={4}>
                    The nodes disagree on {result.disagreements.length} item
                    {result.disagreements.length === 1 ? '' : 's'}
                  </Text>
                  <List size="xs" spacing={2}>
                    {result.disagreements.map((d, i) => (
                      <List.Item key={i}>{d}</List.Item>
                    ))}
                  </List>
                </>
              ) : null}
            </Stack>
          ) : null}
        </div>

        <Group gap="sm">
          <Button
            size="xs"
            onClick={onAdopt}
            disabled={!canWrite}
            title={canWrite ? undefined : blockedReason}
          >
            Review and adopt as revision 1
          </Button>
          <Tooltip label={WHY_NOT_AUTOMATIC} multiline w={340} withArrow>
            <Anchor component="button" type="button" size="xs" underline="always">
              Why does Studio not do this for me?
            </Anchor>
          </Tooltip>
        </Group>
      </Stack>
    </Alert>
  );
}
