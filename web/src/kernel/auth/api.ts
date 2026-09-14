import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, request } from "../api/request.ts";
import { clearDismissedNotices } from "../useDismissedNotice.ts";
import type { components } from "../api/schema.d.ts";

type Schemas = components["schemas"];

export type GrantView = Schemas["GrantView"];
export type IdentityProviderView = Schemas["IdentityProviderView"];
export type LoginRequest = Schemas["LoginRequest"];
export type MeView = Schemas["MeView"];

export const keys = {
  authProviders: ['auth', 'providers'] as const,
  me: ['auth', 'me'] as const,
};

/** `retry: false` — a 401 here means "not logged in," which retrying cannot fix. */
export function useMe(): UseQueryResult<MeView, ApiError> {
  return useQuery({
    queryKey: keys.me,
    queryFn: () => request<MeView>("/auth/me"),
    retry: false,
  });
}

/** Public, unauthenticated — the login screen needs this before any session exists. */
export function useAuthProviders(): UseQueryResult<IdentityProviderView[], ApiError> {
  return useQuery({
    queryKey: keys.authProviders,
    queryFn: () => request<IdentityProviderView[]>("/auth/providers"),
    retry: false,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation<MeView, ApiError, LoginRequest>({
    mutationFn: (body) =>
      request<MeView>("/auth/login", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (me) => {
      // Whoever signs in next sees every notice again, including on a shared browser.
      clearDismissedNotices();
      qc.setQueryData(keys.me, me);
    },
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, void>({
    mutationFn: () => request<void>("/auth/logout", { method: "POST" }),
    onSuccess: () => {
      clearDismissedNotices();
      qc.setQueryData(keys.me, undefined);
    },
  });
}
