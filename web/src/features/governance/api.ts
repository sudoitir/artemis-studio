import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';

import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type RuleView = Schemas['RuleView'];
export type RuleRequest = Schemas['RuleRequest'];

export const keys = {
  rules: ['governance', 'rules'] as const,
};

export function useRules(): UseQueryResult<RuleView[], ApiError> {
  return useQuery({
    queryKey: keys.rules,
    queryFn: () => request<RuleView[]>('/governance/rules'),
  });
}

export function useCreateRule() {
  const qc = useQueryClient();
  return useMutation<RuleView, ApiError, RuleRequest>({
    mutationFn: (body) => request<RuleView>('/governance/rules', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.rules }),
  });
}

export function useUpdateRule() {
  const qc = useQueryClient();
  return useMutation<RuleView, ApiError, { ruleId: string; body: RuleRequest }>({
    mutationFn: ({ ruleId, body }) =>
      request<RuleView>(`/governance/rules/${ruleId}`, { method: 'PUT', body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.rules }),
  });
}

export function useDeleteRule() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (ruleId) => request<void>(`/governance/rules/${ruleId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.rules }),
  });
}

/** The request that keeps a rule as it is except for `enabled` — all a built-in rule accepts. */
export function withEnabled(rule: RuleView, enabled: boolean): RuleRequest {
  return {
    addressPattern: rule.addressPattern ?? null,
    target: rule.target,
    selector: rule.selector,
    dataClass: rule.dataClass,
    action: rule.action ?? null,
    enabled,
  };
}
