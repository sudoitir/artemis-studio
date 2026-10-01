import { useState } from 'react';
import { Button, Checkbox, Code, Group, Stack, TagsInput, Text } from '@mantine/core';

import { useDeclareRecommended, type ConfigRecommendationView, type ConfigRecommendationsView } from './api.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { notify, type ActionVerb } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { XmlBlock } from './XmlBlock.tsx';

const DECLARE: ActionVerb = { verb: 'Declare', past: 'Declared', progressive: 'Declaring' };

/**
 * What the capability probe found, as configuration the operator can declare in
 * one action (ADR-0068).
 *
 * <p>Two halves, both always shown. The appliable half becomes a revision over
 * the management API; the manual half names what still needs a `broker.xml` edit
 * and a restart, with the fragment to paste. Hiding the second half would teach
 * the operator that the gaps it covers do not exist (non-negotiable #5).
 *
 * <p>Declaring writes nothing to a broker — it saves a revision — so it takes no
 * typed confirmation. The apply that follows is the ordinary one, with the
 * ordinary canary, hazards and confirmation (ADR-0067 D8).
 */
export function RecommendedConfiguration({
  clusterId,
  recommendations,
  onDeclared,
  disabledReason,
}: Readonly<{
  /** Absent before the cluster is registered: the panel then previews and cannot declare. */
  clusterId?: string;
  recommendations: ConfigRecommendationsView;
  /** Open the review drawer on the revision this just saved; absent during registration. */
  onDeclared?: () => void;
  /** Why declaring is unavailable right now, stated rather than hidden. */
  disabledReason?: string;
}>) {
  const appliable = recommendations.recommendations.filter((r) => r.appliable);
  const manual = recommendations.recommendations.filter((r) => !r.appliable);

  const [taken, setTaken] = useState<string[]>(() => appliable.map((r) => r.capability));
  const [roles, setRoles] = useState<Record<string, string[]>>(() =>
    Object.fromEntries(
      appliable.filter((r) => r.section === 'SECURITY_SETTING' && r.match).map((r) => [r.match as string, rolesOf(r)]),
    ),
  );

  const declare = useDeclareRecommended(clusterId ?? '');

  const selected = appliable.filter((r) => taken.includes(r.capability));
  const missingRoles = selected.filter(
    (r) => r.section === 'SECURITY_SETTING' && r.match && (roles[r.match] ?? []).length === 0,
  );

  const blocked =
    disabledReason ??
    (!clusterId ? 'Register the cluster first; a declaration belongs to a registered cluster.' : undefined) ??
    (selected.length === 0 ? 'Choose at least one setting to declare.' : undefined) ??
    (missingRoles.length > 0
      ? `The security setting for ${missingRoles[0].match} would grant nobody anything. Name at least one role.`
      : undefined);

  if (appliable.length === 0 && manual.length === 0) {
    return (
      <EmptyState
        kind="empty"
        title="Nothing to recommend"
        description="Every capability this connection was assessed on is available. There is nothing Studio would change."
      />
    );
  }

  const declareNow = () =>
    declare.mutate(
      { capabilities: taken, roles },
      {
        onSuccess: () => {
          notify.succeeded({ action: DECLARE, subject: 'the recommended settings as a new revision' });
          onDeclared?.();
        },
      },
    );

  return (
    <Stack gap="xl">
      {appliable.length > 0 ? (
        <Section
          title="Studio can apply these"
          description={
            recommendations.seededFrom
              ? `Each entry below is the whole setting as ${recommendations.seededFrom} runs it today, with the` +
                ' recommended keys changed. A runtime write replaces the entry rather than merging into it, so' +
                ' what is listed is exactly what the node will hold afterwards.'
              : 'No node could be read, so these entries carry only the recommended keys. Applying one would' +
                ' replace whatever else the match holds — read a node first if you can.'
          }
        >
          <Checkbox.Group value={taken} onChange={setTaken}>
            <Stack gap="md">
              {appliable.map((r) => (
                <Stack key={r.capability} gap="xs">
                  <Checkbox value={r.capability} label={r.title} />
                  <Text size="sm" c="dimmed" ml="xl">
                    {r.rationale}
                  </Text>
                  <Group gap="xs" ml="xl" wrap="wrap">
                    <Text size="sm">
                      {r.section === 'SECURITY_SETTING' ? 'security-setting' : 'address-setting'} <Code>{r.match}</Code>
                    </Text>
                    {r.keys.map((k) => (
                      <Code key={k}>{k}</Code>
                    ))}
                  </Group>

                  {r.section === 'SECURITY_SETTING' && r.match ? (
                    <TagsInput
                      ml="xl"
                      size="xs"
                      label="Roles that get these permissions"
                      description={
                        rolesOf(r).length > 0
                          ? 'Read from the broker; change it if a different role should hold them.'
                          : 'The broker names no role here, so there is nothing to copy. Name the one your account holds.'
                      }
                      error={
                        taken.includes(r.capability) && (roles[r.match] ?? []).length === 0
                          ? 'At least one role, or this grants nobody anything.'
                          : undefined
                      }
                      value={roles[r.match] ?? []}
                      onChange={(next) => setRoles((prev) => ({ ...prev, [r.match as string]: next }))}
                    />
                  ) : (
                    <ValuePreview recommendation={r} ml />
                  )}

                  {r.manualSnippet ? (
                    <Text size="sm" c="dimmed" ml="xl">
                      Its verdict only reaches Studio once the broker-plugin below is installed too.
                    </Text>
                  ) : null}
                </Stack>
              ))}
            </Stack>
          </Checkbox.Group>

          {clusterId ? (
            <Group gap="sm" align="center">
              <Button
                size="xs"
                loading={declare.isPending}
                disabled={Boolean(blocked)}
                aria-disabled={Boolean(blocked)}
                onClick={declareNow}
              >
                Declare &amp; review the plan
              </Button>
              <Text size="sm" c="dimmed">
                {blocked ?? 'Saves a revision. Nothing reaches a broker until you confirm the plan.'}
              </Text>
            </Group>
          ) : (
            <Text size="sm" c="dimmed">
              {blocked}
            </Text>
          )}

          {declare.isError ? <ErrorState error={declare.error} onRetry={declareNow} /> : null}
        </Section>
      ) : null}

      {manual.length > 0 ? (
        <Section
          title="These still need a broker.xml edit"
          description="No management operation writes any of them, so Studio never will. Paste the fragment and restart the broker."
        >
          {manual.map((r) => (
            <Stack key={`${r.capability}-manual`} gap="xs">
              <Text size="sm">{r.title}</Text>
              <Text size="sm" c="dimmed">
                {r.rationale}
              </Text>
              {r.manualSnippet ? (
                <XmlBlock code={r.manualSnippet.trimEnd()} label={`broker.xml for ${r.title}`} />
              ) : null}
            </Stack>
          ))}
        </Section>
      ) : null}
    </Stack>
  );
}

/** The whole entry that would be written, so a replace holds no surprises. */
function ValuePreview({ recommendation, ml }: Readonly<{ recommendation: ConfigRecommendationView; ml?: boolean }>) {
  // Changed keys first, then the rest alphabetically. The broker answers in its
  // own order, which puts the one key this recommendation is about somewhere in
  // the middle of seventeen it is not changing.
  const changed = (key: string) => recommendation.keys.includes(key);
  const entries = Object.entries(recommendation.values).sort(([a], [b]) => {
    if (changed(a) !== changed(b)) return changed(a) ? -1 : 1;
    return a.localeCompare(b);
  });
  if (entries.length === 0) return null;
  return (
    <Stack gap="xs" ml={ml ? 'xl' : undefined}>
      {entries.map(([key, value]) => (
        <Group key={key} gap="xs" wrap="wrap">
          <Text size="sm" c={changed(key) ? undefined : 'dimmed'} fw={changed(key) ? 600 : undefined}>
            {key}
          </Text>
          <Text size="sm" ff="monospace">
            {String(value)}
          </Text>
          {changed(key) ? null : (
            <Text size="sm" c="dimmed">
              (unchanged)
            </Text>
          )}
        </Group>
      ))}
    </Stack>
  );
}

function rolesOf(r: ConfigRecommendationView): string[] {
  const all = new Set<string>();
  Object.values(r.roles).forEach((names) => names.forEach((n) => all.add(n)));
  return [...all].sort((a, b) => a.localeCompare(b));
}
