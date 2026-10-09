import { lazy, Suspense, useState } from 'react';
import { Button, Modal, ScrollArea } from '@mantine/core';

import { useDiscardGuard } from '../../ui/DialogActions.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';

/** The registration form loads when a dialog is first opened, not with the shell that holds the switcher. */
const RegisterClusterForm = lazy(() =>
  import('./RegisterCluster.tsx').then((m) => ({ default: m.RegisterClusterForm })),
);

/**
 * Registration in a dialog: the form arrives behind a loader, so opening it is never a wait on nothing. It
 * opens at the top rather than centred, since it grows as the check reports, and a half-filled form is not
 * lost to a click outside.
 */
export function RegisterClusterDialog({ opened, onClose }: Readonly<{ opened: boolean; onClose: () => void }>) {
  const [dirty, setDirty] = useState(false);
  const close = () => {
    setDirty(false);
    onClose();
  };
  const guard = useDiscardGuard(dirty, close);
  return (
    <Modal
      opened={opened}
      {...guard.modalProps}
      title="Register cluster"
      size="lg"
      scrollAreaComponent={ScrollArea.Autosize}
    >
      {guard.prompt}
      <Suspense fallback={<LoadingState label="Loading the registration form" blockSize="24rem" />}>
        <RegisterClusterForm onDone={close} onDirtyChange={setDirty} />
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
