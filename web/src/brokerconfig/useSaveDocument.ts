import { useSaveBrokerConfig, type ConfigDeclarationView, type ConfigDocumentView } from '../api/client.ts';

/**
 * Saving an edit means saving the whole document as a new revision, against the
 * revision the editor was opened on. Every editor does exactly this, so the
 * shape lives once.
 */
export function useSaveDocument(declaration: ConfigDeclarationView, onSaved: () => void) {
  const save = useSaveBrokerConfig(declaration.clusterId);
  return {
    save: (document: ConfigDocumentView, note: string) =>
      save.mutate(
        { document, expectedRevision: declaration.revision, note },
        { onSuccess: onSaved },
      ),
    isPending: save.isPending,
    error: save.isError ? save.error : null,
    reset: save.reset,
  };
}
