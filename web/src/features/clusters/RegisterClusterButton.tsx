import { lazy, Suspense, useState } from 'react';
import { Button, Modal, ScrollArea } from '@mantine/core';

import { LoadingState } from '../../ui/LoadingState.tsx';

/** The registration form loads when a dialog is first opened, not with the shell that holds the switcher. */
const RegisterClusterForm = lazy(() =>
  import('./RegisterCluster.tsx').then((m) => ({ default: m.RegisterClusterForm })),
);

/** Registration in a dialog: the form arrives behind a loader, so opening it is never a wait on nothing. */
export function RegisterClusterDialog({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title="Register cluster"
      size="lg"
      centered
      scrollAreaComponent={ScrollArea.Autosize}
    >
      <Suspense fallback={<LoadingState label="Loading the registration form" blockSize="24rem" />}>
        <RegisterClusterForm onRegistered={onClose} />
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
