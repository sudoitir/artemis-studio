import { Notice } from './Notice.tsx';

/**
 * Says that a history was read from the database's read replica, which can be a few seconds behind the
 * primary, so an event from the last moments may not be listed yet. Renders nothing when it was not.
 */
export function ReplicaLagNotice({ mayLag }: Readonly<{ mayLag: boolean | undefined }>) {
  if (!mayLag) return null;
  return (
    <Notice title="Read from a replica" tone="info">
      This list comes from a read replica of the database, which can be a few seconds behind. The newest entries may not
      be shown yet.
    </Notice>
  );
}
