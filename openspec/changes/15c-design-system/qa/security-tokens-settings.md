# QA log: security-tokens-settings

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### security-tokens-settings-1 [S2 · reliability · page] Mint form's grantable permissions memo ignores the user's grants loading

- Where: `web/src/features/apitokens/MintKeyForm.tsx:37`
- Evidence: The useMemo calls can(), which reads useMe().data, but its deps are [catalogue.data, clusterId, scope] and exhaustive-deps is suppressed. If the catalogue resolves before /me (or /me refetches after a role change), `available` keeps the stale result. It can stay empty and show 'You hold nothing at this scope', with Create disabled, until the user changes scope.
- Fix: Add the grants to the deps (the `grants` returned by useCan), or drop the memo and filter during render.
- Status: fixed (the form computes what the user can delegate while rendering, from the user's grants as they stand, with no memo and no suppressed lint rule. `ApiKeysPanel.test.tsx` delays the user's grants until after the catalogue and asserts the list follows them: it fails on the old memo)

### security-tokens-settings-2 [S2 · reliability · page] Key revoke success is not announced; outcome only shown by the modal closing

- Where: `web/src/features/apitokens/ApiKeysPanel.tsx:254`
- Evidence: revoke.mutate(token.id, { onSuccess: onClose }) closes the modal and nothing else happens. ApiKeysPanel.tsx and AdminTokensPanel.tsx (line 197) have no aria-live/role=status region. This breaks the .claude/rules/20-frontend.md rule to announce the outcome of a destructive action through aria-live.
- Fix: Show a success notice in a shared aria-live/status region when the revoke succeeds, in both panels.
- Status: fixed (a revoke is announced through `notify`: a polite status naming the key on success, and an assertive alert with the cause and the next step on failure, in both panels. `ApiKeysPanel.test.tsx` and `AdminTokensPanel.test.tsx` assert the success and the failure announcements)

### security-tokens-settings-3 [S3 · reliability · page] Revoke error persists into the next token's revoke modal

- Where: `web/src/features/apitokens/ApiKeysPanel.tsx:238`
- Evidence: RevokeModal stays mounted, and its onClose never calls revoke.reset(), unlike RotateModal. After a failed revoke of key A, closing the modal and opening Revoke for key B shows 'The key was not revoked' with A's error before anything is tried. AdminTokensPanel.tsx:178-197 has the same issue.
- Fix: Wrap onClose with revoke.reset(), as RotateModal does.
- Status: fixed (revoking is a `ConfirmDialog` with the key's name typed, which resets its mutation on close, and a failure is a toast, so nothing inline outlives the dialog. `ApiKeysPanel.test.tsx` fails a revoke of one key, closes the dialog, opens the next key's and asserts no trace of the failure in it)

