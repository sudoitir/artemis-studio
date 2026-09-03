import { useState, type ReactNode } from 'react';
import {
  AppShell,
  Burger,
  Group,
  Text,
  Title,
  Stack,
  Badge,
  Card,
  Anchor,
  ThemeIcon,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { Spotlight, spotlight } from '@mantine/spotlight';
import {
  IconTopologyStar3,
  IconSearch,
  IconStack2,
  IconArrowsExchange,
  IconShieldLock,
} from '@tabler/icons-react';

import { branding } from './branding.ts';

// Workspace shell. No data, no routes — a frame for Phase 1+ to build into.
export function App() {
  const [navOpen, { toggle }] = useDisclosure();
  const [count] = useState(0);

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 260, breakpoint: 'sm', collapsed: { mobile: !navOpen } }}
      padding="lg"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="xs">
            <Burger opened={navOpen} onClick={toggle} hiddenFrom="sm" size="sm" />
            <ThemeIcon variant="light" color="pine" radius="md">
              <IconTopologyStar3 size={18} />
            </ThemeIcon>
            <Text fw={600}>{branding.productName}</Text>
            <Badge size="xs" variant="light" color="gray">
              0.1.0 · skeleton
            </Badge>
          </Group>
          <Anchor
            size="sm"
            c="dimmed"
            onClick={() => spotlight.open()}
            component="button"
          >
            <Group gap={6}>
              <IconSearch size={14} />
              <Text size="sm">Search</Text>
            </Group>
          </Anchor>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="md">
        <Stack gap="xs">
          <NavItem icon={<IconTopologyStar3 size={16} />} label="Topology" />
          <NavItem icon={<IconStack2 size={16} />} label="Queues & Addresses" />
          <NavItem icon={<IconArrowsExchange size={16} />} label="Request-Reply" />
          <NavItem icon={<IconShieldLock size={16} />} label="Audit Log" />
          <Text size="xs" c="dimmed" mt="md">
            Nothing is wired yet. See docs/roadmap.md.
          </Text>
        </Stack>
      </AppShell.Navbar>

      <AppShell.Main>
        <Stack maw={720} gap="md">
          <Title order={2}>{branding.productName}</Title>
          <Text c="dimmed">{branding.tagline}</Text>

          <Card withBorder radius="md" padding="lg">
            <Stack gap="xs">
              <Text fw={600}>No clusters registered</Text>
              <Text size="sm" c="dimmed">
                {count} clusters. Cluster registration, topology discovery, and
                the capability probe land in Phase 1.
              </Text>
            </Stack>
          </Card>

          <Text size="xs" c="dimmed">
            {branding.trademarkNotice}
          </Text>
        </Stack>
      </AppShell.Main>

      <Spotlight
        actions={[]}
        nothingFound="Command palette is wired; actions arrive with the features."
        searchProps={{ placeholder: 'Jump to a cluster, queue, or action…' }}
      />
    </AppShell>
  );
}

function NavItem({ icon, label }: { icon: ReactNode; label: string }) {
  return (
    <Group gap="sm" c="dimmed" style={{ cursor: 'not-allowed' }}>
      <ThemeIcon variant="subtle" color="gray" size="sm">
        {icon}
      </ThemeIcon>
      <Text size="sm">{label}</Text>
    </Group>
  );
}
