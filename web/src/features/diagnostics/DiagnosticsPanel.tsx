import { useEffect, useState, type ComponentType } from 'react';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  List,
  Paper,
  Skeleton,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
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
import { download } from '../../ui/download.ts';
import { useDownloadBundle, usePrepareBundle, type BundleView, type SectionView } from './api.ts';
import classes from './Diagnostics.module.css';
import { BUNDLE_PERMISSION, formatBytes } from './format.ts';
import { SectionPreview } from './SectionPreview.tsx';

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

/** Administration → Diagnostics (diagnostics spec): prepare, review and trim, then download a support bundle. */
export function DiagnosticsPanel() {
  const { can, loading } = useCan();
  if (!loading && !can(BUNDLE_PERMISSION)) {
    return (
      <Alert color="gray" title="You cannot create support bundles">
        Support bundles need the global permission &ldquo;Create support bundles&rdquo; ({BUNDLE_PERMISSION}). Ask an
        administrator to add it to one of your roles. Anyone can still report a bug from the user menu.
      </Alert>
    );
  }
  return <BundleFlow />;
}

function BundleFlow() {
  const prepare = usePrepareBundle();
  const bundle = prepare.data;

  if (prepare.isPending) return <PreparingSkeleton />;
  if (!bundle) {
    return <Intro onPrepare={() => prepare.mutate()} error={prepare.isError ? prepare.error.message : null} />;
  }
  return <Review key={bundle.id} bundle={bundle} onPrepareAgain={() => prepare.mutate()} />;
}

function Intro({ onPrepare, error }: Readonly<{ onPrepare: () => void; error: string | null }>) {
  const promise = (Icon: ComponentType<IconProps>, text: string) => (
    <List.Item
      icon={
        <ThemeIcon variant="light" size={24} radius="xl">
          <Icon size={14} aria-hidden />
        </ThemeIcon>
      }
    >
      {text}
    </List.Item>
  );
  return (
    <Stack gap="md" maw={720}>
      {error && (
        <Alert color="red" title="The bundle could not be prepared">
          <Text size="sm">{error}</Text>
          <Text size="sm">Try again; if it keeps failing, report a bug from the user menu with this message.</Text>
        </Alert>
      )}
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <div>
            <Title order={3}>Support bundle</Title>
            <Text size="sm" c="dimmed" mt={4}>
              Everything a maintainer needs to diagnose a problem, in one file: versions and environment, settings,
              health, features and plugins, a thread dump and the recent logs of this {branding.productShortName}.
            </Text>
          </div>
          <List spacing="xs" size="sm" center>
            {promise(IconShieldLock, 'Passwords, tokens, keys and credentials in URLs are replaced with [redacted].')}
            {promise(IconMailOff, 'No message bodies or properties are included, whatever the governance policy.')}
            {promise(IconWifiOff, 'Built on this server. Nothing is uploaded anywhere; it works offline.')}
            {promise(IconEye, 'You review every section and choose what to keep before anything is downloaded.')}
          </List>
          <Group>
            <Button leftSection={<IconRefresh size={16} aria-hidden />} onClick={onPrepare}>
              Prepare bundle
            </Button>
            <Text size="xs" c="dimmed">
              Downloading a bundle is recorded in the audit log.
            </Text>
          </Group>
        </Stack>
      </Paper>
    </Stack>
  );
}

function PreparingSkeleton() {
  return (
    <Stack gap="md" aria-busy="true" aria-label="Preparing the support bundle">
      <Text size="sm" c="dimmed">
        Collecting and redacting every section…
      </Text>
      <div className={classes.layout}>
        <Stack gap="xs">
          {Object.keys(SECTION_INFO).map((k) => (
            <Skeleton key={k} h={58} radius="md" />
          ))}
        </Stack>
        <Skeleton h={600} radius="md" />
      </div>
    </Stack>
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
          notifications.show({
            color: 'green',
            title: 'Support bundle downloaded',
            message: `${fileName}, ${kept.length} of ${bundle.sections.length} sections. Recorded in the audit log.`,
          });
        },
      },
    );

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <div>
          <Title order={3}>Review the bundle</Title>
          <Text size="sm" c="dimmed">
            This is exactly what the file will contain. Select a section to read it; clear its box to leave it out.
          </Text>
        </div>
        <Button variant="default" leftSection={<IconRefresh size={16} aria-hidden />} onClick={onPrepareAgain}>
          Prepare again
        </Button>
      </Group>

      {expired && (
        <Alert color="yellow" title="This snapshot has expired">
          <Group justify="space-between">
            <Text size="sm">A prepared bundle is kept for ten minutes. Prepare it again to download current data.</Text>
            <Button size="compact-sm" onClick={onPrepareAgain}>
              Prepare again
            </Button>
          </Group>
        </Alert>
      )}

      <div className={classes.layout}>
        <div className={classes.sections} role="group" aria-label="Sections">
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
        </div>
        {current && <SectionPreview key={current.key} section={current} included={!excluded.has(current.key)} />}
      </div>

      <Paper className={classes.footer} p="sm">
        <Group justify="space-between">
          <Group gap="lg">
            <Text size="sm" className={classes.num} aria-live="polite">
              <b>{kept.length}</b> of {bundle.sections.length} sections · {formatBytes(keptBytes)} before compression
            </Text>
            {downloadBundle.isSuccess && (
              <Text size="sm" c="dimmed" role="status">
                Downloaded {downloadBundle.data.fileName}, recorded in the audit log.
              </Text>
            )}
            {!expired && (
              <Text size="sm" c="dimmed" className={classes.num}>
                Expires in {formatRemaining(remaining)}
              </Text>
            )}
          </Group>
          <Group gap="sm">
            {kept.length === 0 && (
              <Text size="sm" c="dimmed">
                Keep at least one section to download.
              </Text>
            )}
            {downloadBundle.isError && !expired && (
              <Text size="sm" c="red" role="alert">
                The download failed: {downloadBundle.error.message}
              </Text>
            )}
            <Button
              leftSection={<IconDownload size={16} aria-hidden />}
              onClick={onDownload}
              loading={downloadBundle.isPending}
              disabled={kept.length === 0 || expired}
            >
              Download bundle
            </Button>
          </Group>
        </Group>
      </Paper>
    </Stack>
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
      <Checkbox mt={3} checked={included} onChange={onToggle} aria-label={`Include ${section.title}`} />
      <button
        type="button"
        className={`${classes.sectionText} ${classes.sectionButton}`}
        onClick={onSelect}
        aria-pressed={selected}
        aria-label={`Show ${section.title}`}
      >
        <Group gap={6} wrap="nowrap">
          <Icon size={16} aria-hidden />
          <Text size="sm" fw={600} td={included ? undefined : 'line-through'}>
            {section.title}
          </Text>
        </Group>
        {info && (
          <Text size="xs" c="dimmed" mt={2}>
            {info.description}
          </Text>
        )}
        <Group gap={6} mt={6}>
          <Badge size="xs" variant="default" className={classes.num}>
            {formatBytes(section.bytes)}
          </Badge>
          {section.redactions > 0 && (
            <Badge size="xs" variant="light" color="yellow" leftSection={<IconShieldLock size={10} aria-hidden />}>
              {section.redactions} redacted
            </Badge>
          )}
        </Group>
      </button>
    </div>
  );
}
