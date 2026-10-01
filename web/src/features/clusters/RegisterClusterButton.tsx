import { lazy, Suspense, useState } from 'react';
import { Button, Modal, ScrollArea } from '@mantine/core';

import { LoadingState } from '../../ui/LoadingState.tsx';

/**
 * The registration form and the xyflow canvas it draws come with it, so they load when a dialog is
 * first opened, not with the shell that holds the switcher and the settings page.
 */
const RegisterClusterPanel = lazy(() =>
  import('./RegisterCluster.tsx').then((m) => ({ default: m.RegisterClusterPanel })),
);

/** Registration in a dialog: the form arrives behind a loader, so opening it is never a wait on nothing. */
export function RegisterClusterDialog({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title="Register cluster"
      size="xl"
      centered
      scrollAreaComponent={ScrollArea.Autosize}
    >
      <Suspense fallback={<LoadingState label="Loading the registration form" blockSize="24rem" />}>
        <RegisterClusterPanel onRegistered={onClose} />
      </Suspense>
    </Modal>
  );
}

/** The post-empty affordance: a button that opens the form in a dialog. */
export function RegisterClusterButton() {
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button variant="default" size="xs" onClick={() => setOpen(true)}>
        Register cluster
      </Button>
      <RegisterClusterDialog opened={open} onClose={() => setOpen(false)} />
    </>
  );
}
