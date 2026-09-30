import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, request } from '../../kernel/api/request.ts';
import type { components } from '../../kernel/api/schema.d.ts';

type Schemas = components['schemas'];

export type CreateTokenRequest = Schemas['CreateTokenRequest'];
export type CreatedTokenView = Schemas['CreatedTokenView'];
export type TokenGrantRequest = Schemas['TokenGrantRequest'];
export type TokenView = Schemas['TokenView'];
export type TokenPolicyView = Schemas['TokenPolicyView'];
export type UsageView = Schemas['UsageView'];
export type McpToolView = Schemas['McpToolView'];

export type UsagePeriod = 1 | 7 | 30;

export const keys = {
  tokens: ['tokens'] as const,
  adminTokens: ['tokens', 'admin'] as const,
  policy: ['tokens', 'policy'] as const,
  mcpTools: ['mcp', 'tools'] as const,
  usage: (scope: 'own' | 'admin', tokenId: string, days: UsagePeriod) => ['tokens', 'usage', scope, tokenId, days],
};

export function useTokens(): UseQueryResult<TokenView[], ApiError> {
  return useQuery({
    queryKey: keys.tokens,
    queryFn: () => request<TokenView[]>('/tokens'),
  });
}

/** Every user's tokens, for holders of `token:admin`. */
export function useAdminTokens(): UseQueryResult<TokenView[], ApiError> {
  return useQuery({
    queryKey: keys.adminTokens,
    queryFn: () => request<TokenView[]>('/admin/tokens'),
  });
}

export function useTokenPolicy(): UseQueryResult<TokenPolicyView, ApiError> {
  return useQuery({
    queryKey: keys.policy,
    queryFn: () => request<TokenPolicyView>('/tokens/policy'),
  });
}

/** The MCP tools a key can be restricted to; `null` when the MCP server is disabled on this installation. */
export function useMcpTools(): UseQueryResult<McpToolView[] | null, ApiError> {
  return useQuery({
    queryKey: keys.mcpTools,
    queryFn: async () => {
      try {
        return await request<McpToolView[]>('/mcp/tools');
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) {
          return null;
        }
        throw e;
      }
    },
  });
}

export function useTokenUsage(
  scope: 'own' | 'admin',
  tokenId: string,
  days: UsagePeriod,
): UseQueryResult<UsageView, ApiError> {
  const base = scope === 'admin' ? '/admin/tokens' : '/tokens';
  return useQuery({
    queryKey: keys.usage(scope, tokenId, days),
    queryFn: () => request<UsageView>(`${base}/${tokenId}/usage?days=${days}`),
  });
}

export function useCreateToken() {
  const qc = useQueryClient();
  return useMutation<CreatedTokenView, ApiError, CreateTokenRequest>({
    mutationFn: (body) =>
      request<CreatedTokenView>('/tokens', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tokens }),
  });
}

export function useRotateToken() {
  const qc = useQueryClient();
  return useMutation<CreatedTokenView, ApiError, string>({
    mutationFn: (tokenId) => request<CreatedTokenView>(`/tokens/${tokenId}/rotate`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tokens }),
  });
}

export function useRevokeToken() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (tokenId) => request<void>(`/tokens/${tokenId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tokens }),
  });
}

/** An administrator revokes any user's token. */
export function useAdminRevokeToken() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (tokenId) => request<void>(`/admin/tokens/${tokenId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tokens }),
  });
}
