import { Notice } from '../../ui/Notice.tsx';
import { useGateStatus } from './api.ts';
import classes from './Approvals.module.css';

/**
 * Above every page while the deployment's break-glass setting is on: approval checks are bypassed, so every
 * operator must know. It cannot be dismissed; it goes when the setting does.
 */
export function BreakGlassBanner() {
  const status = useGateStatus();
  if (!status.data?.breakGlass) return null;
  return (
    <div className={classes.banner}>
      <Notice tone="danger" title="Break-glass is on">
        Approval checks are bypassed by the deployment&apos;s break-glass setting. Every bypassed operation is audited.
      </Notice>
    </div>
  );
}
