import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiError, request } from "../../kernel/api/request.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type ChangePasswordRequest = Schemas["ChangePasswordRequest"];

export const keys = {
  me: ['auth', 'me'] as const,
};

export function useChangePassword() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, ChangePasswordRequest>({
    mutationFn: (body) =>
      request<void>("/auth/password", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    // The server clears `mustChangePassword` and re-authenticates the same
    // session, but the cached `me` still says the account is locked — without
    // this refetch RootLayout bounces the user straight back to /change-password.
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.me }),
  });
}
