import { Notice } from '../../ui/Notice.tsx';
import { useGateStatus } from './api.ts';

/**
 * Shown while the approval gate holds requests but fewer than two people may approve them. A request needs a
 * person other than its requester, so with one approver nothing can be approved, not even the removal of the
 * policy that holds it. Studio refuses an access change that would take the approvers below two; this says so
 * when it is already the case (a directory sync, a deleted account), and what to do. Nothing while approvals are
 * off, not yet holding anything, or well staffed.
 */
export function ApproverQuorumNotice() {
  const status = useGateStatus().data;
  if (!status?.armed || !status.enforcing || status.quorate) return null;
  const count = status.approvers === 1 ? 'one person' : `${status.approvers} people`;
  return (
    <Notice tone="warning" title="Approvals need a second approver">
      Approvals are on, and only {count} may approve them. A request cannot be approved by the person who made it, so
      nothing can be approved until another person holds the approver permission. Grant it under Administration, Roles
      or Users. If nobody can, whoever runs this deployment can set the break-glass switch to recover.
    </Notice>
  );
}
