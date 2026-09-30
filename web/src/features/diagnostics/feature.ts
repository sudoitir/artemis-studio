import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { DiagnosticsPanel } from './DiagnosticsPanel.tsx';
import { ReportBugDialog } from './ReportBugDialog.tsx';

/** Administration → Diagnostics, the support bundle; and "Report a bug" in the user menu (diagnostics spec). */
export const diagnosticsFeature = defineFeature({
  contract: CONTRACT,
  id: 'diagnostics',
  slots: {
    'admin.tabs': [{ id: 'diagnostics', order: 90, title: 'Diagnostics', Component: DiagnosticsPanel }],
    'shell.userMenu': [{ id: 'report-bug', order: 10, title: 'Report a bug…', Component: ReportBugDialog }],
  },
});
