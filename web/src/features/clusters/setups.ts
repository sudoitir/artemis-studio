/** The shapes of deployment the registration form tells apart, and what each needs typed in. */
export type Setup = 'single' | 'pair' | 'cluster';

export interface SetupGuide {
  value: Setup;
  title: string;
  description: string;
  /** What to enter in the management URLs field, for this shape. */
  urls: string;
  rows: number;
}

export const SETUPS: readonly SetupGuide[] = [
  {
    value: 'single',
    title: 'Single broker',
    description: 'One broker, no backup.',
    urls: "Enter the broker's management URL.",
    rows: 1,
  },
  {
    value: 'pair',
    title: 'Primary and backup',
    description: 'A replicated or shared-store pair.',
    urls: "Enter the primary's management URL. Studio finds its backup; add the backup's URL only if Studio cannot reach it through the primary.",
    rows: 2,
  },
  {
    value: 'cluster',
    title: 'Cluster',
    description: 'Several brokers joined by cluster connections.',
    urls: 'Enter one management URL per broker Studio can reach, one per line. Brokers that join later are found on their own.',
    rows: 3,
  },
];
