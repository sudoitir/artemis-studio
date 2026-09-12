import { useSaveBrokerConfig, type ConfigDeclarationView, type ConfigDocumentView } from '../api/client.ts';

/**
 * Saving an edit means saving the whole document as a new revision, against the
 * revision the editor was opened on. Every editor does exactly this, so the
 * shape lives once.
 */
export function useSaveDocument(declaration: ConfigDeclarationView, onSaved: () => void) {
  const save = useSaveBrokerConfig(declaration.clusterId);
  return {
    /**
     * `source` records where the document came from. An adoption must say so: it is
     * the one save that closes drift findings without any broker being written, and
     * the server refuses it without `confirm` when it would.
     */
    save: (
      document: ConfigDocumentView,
      note: string,
      provenance?: { source: 'EDIT' | 'IMPORT_XML' | 'ADOPT'; confirm?: string },
    ) =>
      save.mutate(
        { document, expectedRevision: declaration.revision, note, ...provenance },
        { onSuccess: onSaved },
      ),
    isPending: save.isPending,
    error: save.isError ? save.error : null,
    reset: save.reset,
  };
}
