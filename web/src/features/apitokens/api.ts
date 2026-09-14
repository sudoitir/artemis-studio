import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, request } from "../../kernel/api/request.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type CreateTokenRequest = Schemas["CreateTokenRequest"];
export type CreatedTokenView = Schemas["CreatedTokenView"];
export type TokenGrantRequest = Schemas["TokenGrantRequest"];
export type TokenView = Schemas["TokenView"];

export const keys = {
  tokens: ['tokens'] as const,
};

export function useTokens(): UseQueryResult<TokenView[], ApiError> {
  return useQuery({
    queryKey: keys.tokens,
    queryFn: () => request<TokenView[]>("/tokens"),
  });
}

export function useCreateToken() {
  const qc = useQueryClient();
  return useMutation<CreatedTokenView, ApiError, CreateTokenRequest>({
    mutationFn: (body) =>
      request<CreatedTokenView>("/tokens", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tokens }),
  });
}

export function useRevokeToken() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (tokenId) =>
      request<void>(`/tokens/${tokenId}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.tokens }),
  });
}
