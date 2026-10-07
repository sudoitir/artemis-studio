import type { Column } from '../../ui/table/index.ts';

/** One change of a draft under review: the setting, where it is and the value it now has and would have. */
export interface ReviewRow {
  key: string;
  setting: string;
  category: string;
  current: string;
  next: string;
}

/** The review dialog's diff: Setting | Current | New. */
export function reviewColumns(): Column<ReviewRow>[] {
  return [
    {
      id: 'setting',
      header: 'Setting',
      accessor: (row) => `${row.setting} (${row.category})`,
      kind: 'text',
      priority: 'essential',
      wrap: true,
    },
    {
      id: 'current',
      header: 'Current',
      accessor: (row) => row.current,
      kind: 'code',
      priority: 'essential',
      wrap: true,
    },
    { id: 'next', header: 'New', accessor: (row) => row.next, kind: 'code', priority: 'essential', wrap: true },
  ];
}
