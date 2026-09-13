import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ApiError, request } from "../../kernel/api/request.ts";
import type { components } from "../../kernel/api/schema.d.ts";

type Schemas = components["schemas"];

export type CreateUserRequest = Schemas["CreateUserRequest"];
export type DefaultRoleRequest = Schemas["DefaultRoleRequest"];
export type GrantRequest = Schemas["GrantRequest"];
export type GroupMappingRequest = Schemas["GroupMappingRequest"];
export type GroupMappingView = Schemas["GroupMappingView"];
export type GroupMappingsView = Schemas["GroupMappingsView"];
export type PermissionView = Schemas["PermissionView"];
export type RoleRequest = Schemas["RoleRequest"];
export type RoleView = Schemas["RoleView"];
export type SetDisabledRequest = Schemas["SetDisabledRequest"];
export type UserView = Schemas["UserView"];

export const keys = {
  groupMappings: (providerId: string) => ['identity', 'providers', providerId, 'group-mappings'] as const,
  permissions: ['permissions'] as const,
  roles: ['roles'] as const,
  users: ['users'] as const,
};

export function useUsers(enabled = true): UseQueryResult<UserView[], ApiError> {
  return useQuery({
    queryKey: keys.users,
    queryFn: () => request<UserView[]>("/users"),
    enabled,
  });
}

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation<UserView, ApiError, CreateUserRequest>({
    mutationFn: (body) =>
      request<UserView>("/users", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useSetUserDisabled() {
  const qc = useQueryClient();
  return useMutation<UserView, ApiError, { userId: string; disabled: boolean }>(
    {
      mutationFn: ({ userId, disabled }) =>
        request<UserView>(`/users/${userId}/disabled`, {
          method: "PUT",
          body: JSON.stringify({ disabled } satisfies SetDisabledRequest),
        }),
      onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
    },
  );
}

export function useAddGrant() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, { userId: string; body: GrantRequest }>({
    mutationFn: ({ userId, body }) =>
      request<void>(`/users/${userId}/grants`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useRemoveGrant() {
  const qc = useQueryClient();
  return useMutation<
    void,
    ApiError,
    { userId: string; roleId: string; scopeType: string; scopeId?: string }
  >({
    mutationFn: ({ userId, roleId, scopeType, scopeId }) => {
      const qs = new URLSearchParams({
        scopeType,
        ...(scopeId ? { scopeId } : {}),
      });
      return request<void>(`/users/${userId}/grants/${roleId}?${qs}`, {
        method: "DELETE",
      });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useRoles(): UseQueryResult<RoleView[], ApiError> {
  return useQuery({
    queryKey: keys.roles,
    queryFn: () => request<RoleView[]>("/roles"),
  });
}

export function usePermissionsCatalogue(): UseQueryResult<
  PermissionView[],
  ApiError
> {
  return useQuery({
    queryKey: keys.permissions,
    queryFn: () => request<PermissionView[]>("/permissions"),
  });
}

export function useCreateRole() {
  const qc = useQueryClient();
  return useMutation<RoleView, ApiError, RoleRequest>({
    mutationFn: (body) =>
      request<RoleView>("/roles", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.roles }),
  });
}

export function useUpdateRole() {
  const qc = useQueryClient();
  return useMutation<RoleView, ApiError, { roleId: string; body: RoleRequest }>(
    {
      mutationFn: ({ roleId, body }) =>
        request<RoleView>(`/roles/${roleId}`, {
          method: "PUT",
          body: JSON.stringify(body),
        }),
      onSuccess: () => qc.invalidateQueries({ queryKey: keys.roles }),
    },
  );
}

export function useDeleteRole() {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (roleId) =>
      request<void>(`/roles/${roleId}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.roles }),
  });
}

const groupMappingsPath = (providerId: string) =>
  `/identity/providers/${encodeURIComponent(providerId)}/group-mappings`;

export function useGroupMappings(
  providerId: string,
): UseQueryResult<GroupMappingsView, ApiError> {
  return useQuery({
    queryKey: keys.groupMappings(providerId),
    queryFn: () => request<GroupMappingsView>(groupMappingsPath(providerId)),
  });
}

export function useCreateGroupMapping(providerId: string) {
  const qc = useQueryClient();
  return useMutation<GroupMappingView, ApiError, GroupMappingRequest>({
    mutationFn: (body) =>
      request<GroupMappingView>(groupMappingsPath(providerId), {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: keys.groupMappings(providerId) }),
  });
}

export function useDeleteGroupMapping(providerId: string) {
  const qc = useQueryClient();
  return useMutation<void, ApiError, string>({
    mutationFn: (mappingId) =>
      request<void>(`${groupMappingsPath(providerId)}/${mappingId}`, {
        method: "DELETE",
      }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: keys.groupMappings(providerId) }),
  });
}

export function useSetDefaultRole(providerId: string) {
  const qc = useQueryClient();
  return useMutation<GroupMappingsView, ApiError, DefaultRoleRequest>({
    mutationFn: (body) =>
      request<GroupMappingsView>(`${groupMappingsPath(providerId)}/default-role`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: keys.groupMappings(providerId) }),
  });
}
