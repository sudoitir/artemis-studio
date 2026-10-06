import type { ReactNode } from 'react';
import { Select } from '@mantine/core';

import { FieldRow } from '../../ui/FieldRow.tsx';
import { useClusters, useEnvironments } from '../clusters/index.ts';
import { GLOBAL_SCOPE, type GrantScope, type ScopeType } from './scope.ts';

const SCOPE_OPTIONS = [
  { value: 'GLOBAL', label: 'Global' },
  { value: 'ENVIRONMENT', label: 'Environment' },
  { value: 'CLUSTER', label: 'Cluster' },
];

/**
 * The scope picker of a grant dialog: Global, Environment or Cluster, and for the last two which one. Takes
 * the form's input props for one `GrantScope` field, so its message sits beside the second select.
 */
export function ScopeFields({
  value = GLOBAL_SCOPE,
  onChange,
  onBlur,
  error,
  'data-path': dataPath,
}: Readonly<{
  value?: GrantScope;
  onChange: (next: GrantScope) => void;
  onBlur?: () => void;
  error?: ReactNode;
  /** The form's marker for focusing the first invalid field; it belongs on the input that can be wrong. */
  'data-path'?: string;
}>) {
  const environments = useEnvironments();
  const clusters = useClusters();
  const environment = value.scopeType === 'ENVIRONMENT';
  const source = environment ? environments : clusters;
  const options = (source.data ?? []).map((item) => ({ value: item.id, label: item.name }));

  return (
    <FieldRow>
      <Select
        label="Scope"
        description="Where the role applies."
        data={SCOPE_OPTIONS}
        value={value.scopeType}
        onChange={(next) => onChange({ scopeType: (next ?? 'GLOBAL') as ScopeType, scopeId: null })}
        allowDeselect={false}
      />
      {value.scopeType === 'GLOBAL' ? null : (
        <Select
          data-path={dataPath}
          label={environment ? 'Environment' : 'Cluster'}
          data={options}
          value={value.scopeId}
          onChange={(next) => onChange({ ...value, scopeId: next })}
          onBlur={onBlur}
          error={error}
          searchable
          nothingFoundMessage={environment ? 'No environments' : 'No clusters'}
          placeholder={source.isPending ? 'Loading' : `Select ${environment ? 'an environment' : 'a cluster'}`}
          required
        />
      )}
    </FieldRow>
  );
}
