import { useState } from 'react';
import { Alert, Badge, Button, Checkbox, Code, Group, Stack, TagsInput, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { useNavigate } from '@tanstack/react-router';

import { useDeclareRecommended, type ConfigRecommendationView, type ConfigRecommendationsView } from '../api/client.ts';

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
  disabledReason,
}: {
  /** Absent before the cluster is registered: the panel then previews and cannot declare. */
  clusterId?: string;
  recommendations: ConfigRecommendationsView;
  /** Why declaring is unavailable right now, stated rather than hidden. */
  disabledReason?: string;
}) {
  const appliable = recommendations.recommendations.filter((r) => r.appliable);
  const manual = recommendations.recommendations.filter((r) => !r.appliable);

  const [taken, setTaken] = useState<string[]>(() => appliable.map((r) => r.capability));
  const [roles, setRoles] = useState<Record<string, string[]>>(() =>
    Object.fromEntries(
      appliable
        .filter((r) => r.section === 'SECURITY_SETTING' && r.match)
        .map((r) => [r.match as string, rolesOf(r)]),
    ),
  );

  const navigate = useNavigate();
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
      <Alert variant="light" color="green" title="Nothing to recommend">
        <Text size="sm">
          Every capability this connection was assessed on is available. There is nothing Studio would change.
        </Text>
      </Alert>
    );
  }

  return (
    <Stack gap="md">
      {appliable.length > 0 ? (
        <Stack gap="sm">
          <Stack gap={2}>
            <Text size="sm" fw={600}>
              Studio can apply these
            </Text>
            <Text size="xs" c="dimmed">
              {recommendations.seededFrom
                ? `Each entry below is the whole setting as ${recommendations.seededFrom} runs it today, with the` +
                  ' recommended keys changed. A runtime write replaces the entry rather than merging into it, so' +
                  ' what is listed is exactly what the node will hold afterwards.'
                : 'No node could be read, so these entries carry only the recommended keys. Applying one would' +
                  ' replace whatever else the match holds — read a node first if you can.'}
            </Text>
          </Stack>

          <Checkbox.Group value={taken} onChange={setTaken}>
            <Stack gap="sm">
              {appliable.map((r) => (
                <Stack key={r.capability} gap={6}>
                  <Checkbox value={r.capability} label={r.title} />
                  <Text size="xs" c="dimmed" ml="xl">
                    {r.rationale}
                  </Text>
                  <Group gap={6} ml="xl" wrap="wrap">
                    <Text size="xs">
                      {r.section === 'SECURITY_SETTING' ? 'security-setting' : 'address-setting'} <Code>{r.match}</Code>
                    </Text>
                    {r.keys.map((k) => (
                      <Badge key={k} size="xs" variant="light">
                        {k}
                      </Badge>
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
                    <Text size="xs" c="dimmed" ml="xl">
                      Its verdict only reaches Studio once the broker-plugin below is installed too.
                    </Text>
                  ) : null}
                </Stack>
              ))}
            </Stack>
          </Checkbox.Group>

          {clusterId ? (
            <Group gap="xs" align="center">
              <Button
                size="xs"
                loading={declare.isPending}
                disabled={Boolean(blocked)}
                aria-disabled={Boolean(blocked)}
                onClick={() =>
                  declare.mutate(
                    { capabilities: taken, roles },
                    {
                      onSuccess: () =>
                        navigate({ to: `/clusters/${clusterId}/configuration/apply` }),
                    },
                  )
                }
              >
                Declare &amp; open the plan
              </Button>
              <Text size="xs" c="dimmed">
                {blocked ??
                  'Saves a revision. Nothing reaches a broker until you confirm the plan on the next screen.'}
              </Text>
            </Group>
          ) : (
            <Text size="xs" c="dimmed">
              {blocked}
            </Text>
          )}

          {declare.isError ? (
            <Alert color="red" variant="light" title={declare.error.title} role="alert">
              {declare.error.message}
            </Alert>
          ) : null}
        </Stack>
      ) : null}

      {manual.length > 0 ? (
        <Stack gap="sm">
          <Stack gap={2}>
            <Text size="sm" fw={600}>
              These still need a broker.xml edit
            </Text>
            <Text size="xs" c="dimmed">
              No management operation writes any of them, so Studio never will. Paste the fragment and restart the
              broker.
            </Text>
          </Stack>
          {manual.map((r) => (
            <Stack key={`${r.capability}-manual`} gap={4}>
              <Text size="sm">{r.title}</Text>
              <Text size="xs" c="dimmed">
                {r.rationale}
              </Text>
              {r.manualSnippet ? <CodeHighlight code={r.manualSnippet.trimEnd()} language="xml" /> : null}
            </Stack>
          ))}
        </Stack>
      ) : null}
    </Stack>
  );
}

/** The whole entry that would be written, so a replace holds no surprises. */
function ValuePreview({ recommendation, ml }: { recommendation: ConfigRecommendationView; ml?: boolean }) {
  const entries = Object.entries(recommendation.values);
  if (entries.length === 0) return null;
  return (
    <Stack gap={2} ml={ml ? 'xl' : undefined}>
      {entries.map(([key, value]) => (
        <Group key={key} gap={6} wrap="nowrap">
          <Text size="xs" c={recommendation.keys.includes(key) ? undefined : 'dimmed'} fw={recommendation.keys.includes(key) ? 600 : undefined}>
            {key}
          </Text>
          <Text size="xs" ff="monospace">
            {String(value)}
          </Text>
          {recommendation.keys.includes(key) ? null : (
            <Text size="xs" c="dimmed">
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
  return [...all].sort();
}
