import { useEffect, useState, type ComponentType } from 'react';
import { Button, Checkbox, Group, List, Stack, Text, ThemeIcon, VisuallyHidden } from '@mantine/core';
import {
  IconActivityHeartbeat,
  IconAdjustments,
  IconDownload,
  IconEye,
  IconFileText,
  IconInfoCircle,
  IconMailOff,
  IconPlug,
  IconRefresh,
  IconShieldLock,
  IconStack2,
  IconWifiOff,
  type IconProps,
} from '@tabler/icons-react';

import { branding } from '../../branding.ts';
import { useCan } from '../../kernel/auth/useCan.ts';
import { CapabilityGate } from '../../ui/CapabilityGate.tsx';
import type { GateVerdict } from '../../ui/capabilityGate.ts';
import { download } from '../../ui/download.ts';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { notify } from '../../ui/notify.ts';
import { Section } from '../../ui/Section.tsx';
import { StatusBadge } from '../../ui/StatusBadge.tsx';
import { useDownloadBundle, usePrepareBundle, type BundleView, type SectionView } from './api.ts';
import classes from './Diagnostics.module.css';
import { BUNDLE_PERMISSION, formatBytes } from './format.ts';
import { SectionPreview } from './SectionPreview.tsx';

const DOWNLOAD = { verb: 'Download', past: 'Downloaded', progressive: 'Downloading' } as const;

/** What each section holds, in words an administrator can decide on. Unknown keys still render. */
const SECTION_INFO: Record<string, { description: string; Icon: ComponentType<IconProps> }> = {
  about: { description: 'Versions, Java, OS, database and the registered clusters', Icon: IconInfoCircle },
  settings: { description: 'Effective settings and secret-key status, never secret values', Icon: IconAdjustments },
  health: { description: 'Every health check with its details', Icon: IconActivityHeartbeat },
  plugins: { description: 'Built-in features and installed plugins with versions', Icon: IconPlug },
  threads: { description: 'What every thread is doing right now', Icon: IconStack2 },
  logs: { description: 'The most recent log lines since start', Icon: IconFileText },
};

function formatRemaining(ms: number): string {
  const s = Math.max(0, Math.ceil(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** Why the bundle cannot be prepared, when the caller's grants say so; grants still loading offer it. */
function bundleGate(allowed: boolean, loading: boolean): GateVerdict {
  if (loading || allowed) return { kind: 'allowed', uncertain: false };
  return {
    kind: 'blocked',
    reason: `Support bundles need the global permission "Create support bundles" (${BUNDLE_PERMISSION}). Ask an administrator to add it to one of your roles. Anyone can still report a bug from the user menu.`,
  };
}

/** Administration → Diagnostics (diagnostics spec): prepare, review and trim, then download a support bundle. */
export function DiagnosticsPanel() {
  const { can, loading } = useCan();
  return <BundleFlow gate={bundleGate(can(BUNDLE_PERMISSION), loading)} />;
}

function BundleFlow({ gate }: Readonly<{ gate: GateVerdict }>) {
  const prepare = usePrepareBundle();
  const bundle = prepare.data;

  if (prepare.isPending) return <PreparingSkeleton />;
  if (!bundle) {
    return <Intro gate={gate} onPrepare={() => prepare.mutate()} error={prepare.isError ? prepare.error : null} />;
  }
  return <Review key={bundle.id} bundle={bundle} onPrepareAgain={() => prepare.mutate()} />;
}

function Intro({
  gate,
  onPrepare,
  error,
}: Readonly<{ gate: GateVerdict; onPrepare: () => void; error: Error | null }>) {
  const promise = (Icon: ComponentType<IconProps>, text: string) => (
    <List.Item
      icon={
        <ThemeIcon variant="light" size="1.5rem" radius="xl">
          <Icon size="0.875rem" aria-hidden />
        </ThemeIcon>
      }
    >
      {text}
    </List.Item>
  );
  return (
    <div className={classes.intro}>
      <Section
        title="Support bundle"
        variant="card"
        description={`Everything a maintainer needs to diagnose a problem, in one file: versions and environment, settings, health, features and plugins, a thread dump and the recent logs of this ${branding.productShortName}.`}
      >
        {error ? <ErrorState error={error} onRetry={onPrepare} /> : null}
        <List spacing="xs" size="sm" center>
          {promise(IconShieldLock, 'Passwords, tokens, keys and credentials in URLs are replaced with [redacted].')}
          {promise(IconMailOff, 'No message bodies or properties are included, whatever the governance policy.')}
          {promise(IconWifiOff, 'Built on this server. Nothing is uploaded anywhere; it works offline.')}
          {promise(IconEye, 'You review every section and choose what to keep before anything is downloaded.')}
        </List>
        <Group>
          <CapabilityGate verdict={gate} what="preparing a support bundle">
            <Button
              leftSection={<IconRefresh size="1rem" aria-hidden />}
              disabled={gate.kind === 'blocked'}
              onClick={onPrepare}
            >
              Prepare bundle
            </Button>
          </CapabilityGate>
          <Text size="xs" className={classes.hint}>
            Downloading a bundle is recorded in the audit log.
          </Text>
        </Group>
      </Section>
    </div>
  );
}

/** Holds the review's size while the bundle is built, so the page does not jump when it arrives. */
function PreparingSkeleton() {
  return (
    <Section title="Preparing the bundle" description="Collecting and redacting every section…">
      <LoadingState label="Preparing the support bundle" blockSize="40rem" />
    </Section>
  );
}

/** Ticks once a second while mounted, for the snapshot's expiry countdown. */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}

function Review({ bundle, onPrepareAgain }: Readonly<{ bundle: BundleView; onPrepareAgain: () => void }>) {
  const [excluded, setExcluded] = useState<Set<string>>(() => new Set());
  const [selected, setSelected] = useState(bundle.sections[0]?.key ?? '');
  const downloadBundle = useDownloadBundle();
  const now = useNow();
  const remaining = new Date(bundle.expiresAt).getTime() - now;
  const expired = remaining <= 0 || downloadBundle.error?.status === 404;

  const kept = bundle.sections.filter((s) => !excluded.has(s.key));
  const keptBytes = kept.reduce((sum, s) => sum + s.bytes, 0);
  const current = bundle.sections.find((s) => s.key === selected) ?? bundle.sections[0];

  const toggle = (key: string) =>
    setExcluded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const onDownload = () =>
    downloadBundle.mutate(
      { id: bundle.id, sections: kept.map((s) => s.key) },
      {
        onSuccess: ({ fileName, blob }) => {
          download(fileName, blob);
          notify.succeeded({
            action: DOWNLOAD,
            subject: `the support bundle, ${fileName} (${kept.length} of ${bundle.sections.length} sections, recorded in the audit log)`,
          });
        },
      },
    );

  return (
    <Section
      title="Review the bundle"
      description="This is exactly what the file will contain. Select a section to read it; clear its box to leave it out."
      actions={
        <Button variant="default" leftSection={<IconRefresh size="1rem" aria-hidden />} onClick={onPrepareAgain}>
          Prepare again
        </Button>
      }
    >
      {expired && (
        <Group justify="space-between" role="status">
          <Stack gap="xs" align="flex-start">
            <StatusBadge tone="warning">Expired</StatusBadge>
            <Text size="sm" fw={600}>
              This snapshot has expired
            </Text>
            <Text size="sm">A prepared bundle is kept for ten minutes. Prepare it again to download current data.</Text>
          </Stack>
          <Button size="compact-sm" onClick={onPrepareAgain}>
            Prepare again
          </Button>
        </Group>
      )}

      <div className={classes.layout}>
        <fieldset className={classes.sections}>
          <VisuallyHidden component="legend">Sections</VisuallyHidden>
          {bundle.sections.map((section) => (
            <SectionRow
              key={section.key}
              section={section}
              included={!excluded.has(section.key)}
              selected={section.key === current?.key}
              onSelect={() => setSelected(section.key)}
              onToggle={() => toggle(section.key)}
            />
          ))}
        </fieldset>
        {current && <SectionPreview key={current.key} section={current} included={!excluded.has(current.key)} />}
      </div>

      <div className={classes.footer}>
        <Group justify="space-between">
          <Group gap="lg">
            <Text size="sm" className={classes.num} aria-live="polite">
              <b>{kept.length}</b> of {bundle.sections.length} sections · {formatBytes(keptBytes)} before compression
            </Text>
            {downloadBundle.isSuccess && (
              <Text size="sm" className={classes.hint} role="status">
                Downloaded {downloadBundle.data.fileName}, recorded in the audit log.
              </Text>
            )}
            {!expired && (
              <Text size="sm" className={`${classes.hint} ${classes.num}`}>
                Expires in {formatRemaining(remaining)}
              </Text>
            )}
          </Group>
          <Group gap="sm">
            {kept.length === 0 && (
              <Text size="sm" className={classes.hint}>
                Keep at least one section to download.
              </Text>
            )}
            {downloadBundle.isError && !expired && <ErrorState variant="inline" error={downloadBundle.error} />}
            <Button
              leftSection={<IconDownload size="1rem" aria-hidden />}
              onClick={onDownload}
              loading={downloadBundle.isPending}
              disabled={kept.length === 0 || expired}
            >
              Download bundle
            </Button>
          </Group>
        </Group>
      </div>
    </Section>
  );
}

function SectionRow({
  section,
  included,
  selected,
  onSelect,
  onToggle,
}: Readonly<{
  section: SectionView;
  included: boolean;
  selected: boolean;
  onSelect: () => void;
  onToggle: () => void;
}>) {
  const info = SECTION_INFO[section.key];
  const Icon = info?.Icon ?? IconFileText;
  return (
    <div className={classes.section} data-selected={selected || undefined} data-excluded={!included || undefined}>
      <Checkbox mt="0.1875rem" checked={included} onChange={onToggle} aria-label={`Include ${section.title}`} />
      <button
        type="button"
        className={`${classes.sectionText} ${classes.sectionButton}`}
        onClick={onSelect}
        aria-pressed={selected}
        aria-label={`Show ${section.title}`}
      >
        <Group gap="xs" wrap="nowrap">
          <Icon size="1rem" aria-hidden />
          <Text size="sm" fw={600} td={included ? undefined : 'line-through'}>
            {section.title}
          </Text>
        </Group>
        {info && (
          <Text size="xs" className={classes.hint} mt="0.125rem">
            {info.description}
          </Text>
        )}
        <Group gap="xs" mt="xs">
          <StatusBadge>{formatBytes(section.bytes)}</StatusBadge>
          {section.redactions > 0 && <StatusBadge tone="info">{`${section.redactions} redacted`}</StatusBadge>}
        </Group>
      </button>
    </div>
  );
}
