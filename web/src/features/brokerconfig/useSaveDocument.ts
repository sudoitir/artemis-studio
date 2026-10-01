import { useSaveBrokerConfig, type ConfigDeclarationView, type ConfigDocumentView } from './api.ts';
import { notify, type ActionVerb } from '../../ui/notify.ts';

const SAVE: ActionVerb = { verb: 'Save', past: 'Saved', progressive: 'Saving' };

/**
 * Saving an edit means saving the whole document as a new revision, against the
 * revision the editor was opened on. Every editor does exactly this, so the
 * shape lives once, with its outcomes: the control that saves is busy while the request runs (`isPending`),
 * a failure is the editor's to show (`error`), and a saved revision is announced, because the editor
 * closes and focus moves with it.
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
        {
          onSuccess: (saved) => {
            notify.succeeded({ action: SAVE, subject: `revision ${saved.revision}: ${note}` });
            onSaved();
          },
        },
      ),
    isPending: save.isPending,
    error: save.isError ? save.error : null,
    reset: save.reset,
  };
}
