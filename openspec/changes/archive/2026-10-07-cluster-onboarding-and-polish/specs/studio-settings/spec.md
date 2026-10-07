# Spec Delta: studio-settings

## REMOVED Requirements

### Requirement: Broker credentials can be rotated
**Reason**: Credentials are now edited with the rest of a cluster's connection, dry-run first, under `cluster-registration` "A cluster's connection can be edited after registration".
**Migration**: None. `PUT /clusters/{id}/credentials` is removed; use `PATCH /clusters/{id}`.
