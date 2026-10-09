---
name: studio-safe-interactions
description: >
  Use when building or changing a form with paired or dependent fields, a destructive confirmation,
  or anything the approval gate can hold (an access change, a settings change set, a plugin action,
  an MCP tool) in Artemis Studio. Covers pair validation that clears when the cause is fixed,
  press-and-hold confirmation, and the approval gate's invariants: no per-action requester fields,
  two approvers, and break-glass as the only recovery.
---

# Studio safe interactions

Three mistakes this app has made, and the rule that prevents each.

## 1. A pair of fields (username and password, new password and confirmation)

**The bug.** Mantine's `validateInputOnBlur` checks only the field that was left. A rule that compares two
fields put its message on the first one; filling in the second never re-checked the first, so the red stayed
until the operator deleted a character and typed it again.

**The rule.**
- Put the message on the **member that is missing**, and say what to do: "Enter the password for this
  username, or clear the username." (`pairProblems` in `ui/formPairs.ts`).
- Call `useRevalidatePairs(form, [['username','password']])` so an edit of either member re-checks the
  members that show a message, clearing it once the pair is complete.
- Leaving the first field of a pair is not an error: the second has not been reached. Validate the pair when
  the operator leaves the second, or on submit.
- Test both directions: fill one, leave, fill the other, no message anywhere; clear one again, the message is
  on the empty one.
- Grep traps: a `validate` function that reads another field (`values.` or `(v, values)`) without
  `useRevalidatePairs`; the same message placed on both fields.

## 2. Confirming something that cannot be undone

**The rule (ADR-0186).** `ConfirmDialog tone="danger"` renders `HoldToConfirm`: a button that fills with red
while it is held (1.5 s, `--as-hold-duration`) and acts only when full. Mouse, touch, pen, or Space/Enter.
- The button's label is the action **with its count**: "Delete 37 queues", not "Delete". The dialog's
  `consequence` states the blast radius (resource, nodes, data) first.
- Focus starts on Cancel. Never typing a name, a checkbox, a second click or a countdown.
- Use `tone="danger"` only for something that removes or overwrites. Leaving a page with unsaved edits,
  closing a panel or a plain save is a normal click: do not make operators hold for it.
- Outside a dialog, use `HoldToConfirm` directly (`ui/HoldToConfirm.tsx`; exported from the SDK).
- Tests: `holdButton(button)` from `src/test/hold.ts` (the test setup shortens the hold); a click must do
  nothing; a hold must act once. Keyboard: `holdByKeyboard`.
- Grep traps: `typedName`, `ConfirmByTyping`, `user.type(` into a "Type ... to confirm" field,
  `tone="danger"` on a non-destructive dialog.

## 3. The approval gate (ADR-0179, 0181, 0184, 0187)

- **Asks the requester for nothing.** No reason, note or field per action, in core or in a plugin. A request
  is the operation, its effect and its policy. Only the approver's rejection reason exists. If a policy wants
  context, put it in the operation's display rows or its summary.
- **Two approvers or the gate locks.** A request needs someone other than its requester. With one approver
  every hold is refused, including removing the policy. So: a provider checks `ApproverPool.quorate()` before
  it starts holding and returns `enforcing() == false` while it has no policy; Studio refuses (409
  `approver-quorum`) an access change that would lower the approvers below two while the provider enforces;
  `/gate/status` and the Administration and Approvals notices say when it is already below two.
- **No fail-open path.** A lone administrator must never approve their own request, even audited. The
  recovery is the deployment's break-glass switch (environment only, ADR-0184), and the refusal text says so.
- **A new access-changing path** (a user, role, team, group-mapping or directory sync change) announces itself
  through `AccessChanges`, so the quorum guard sees it. Do not write access tables without it.
- Grep traps: `reason` added to a request or a `Hold`; `X-Studio-Approval-Reason`; `approvalReason`; a plugin
  action that adds its own approval field; a new route that changes who holds the approver permission without
  `AccessChanges`.
