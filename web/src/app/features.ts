import { alertingFeature } from '../features/alerting/feature.ts';
import { apitokensFeature } from '../features/apitokens/feature.ts';
import { auditFeature } from '../features/audit/feature.ts';
import { bulkFeature } from '../features/bulk/feature.ts';
import { brokerconfigFeature } from '../features/brokerconfig/feature.ts';
import { clustersFeature } from '../features/clusters/feature.ts';
import { eventsFeature } from '../features/events/feature.ts';
import { flowFeature } from '../features/flow/feature.ts';
import { governanceFeature } from '../features/governance/feature.ts';
import { identityLocalFeature } from '../features/identity-local/feature.ts';
import { mcpFeature } from '../features/mcp/feature.ts';
import { messagesFeature } from '../features/messages/feature.ts';
import { metricsFeature } from '../features/metrics/feature.ts';
import { queuesFeature } from '../features/queues/feature.ts';
import { resourcesFeature } from '../features/resources/feature.ts';
import { routingFeature } from '../features/routing/feature.ts';
import { rrFeature } from '../features/rr/feature.ts';
import { securityFeature } from '../features/security/feature.ts';
import { settingsFeature } from '../features/settings/feature.ts';
import { sqlFeature } from '../features/sql/feature.ts';
import { triageFeature } from '../features/triage/feature.ts';
import type { StudioFeature } from '../kernel/feature.ts';

/**
 * The composition root (ADR-0069): the one list of frontend features. Each feature uses its backend
 * module's id, so the manifest decides which of them this installation offers. The order is the order
 * of their palette groups; nav entries and slot contributions carry their own.
 */
export const FEATURES: StudioFeature[] = [
  clustersFeature,
  metricsFeature,
  alertingFeature,
  flowFeature,
  triageFeature,
  rrFeature,
  queuesFeature,
  messagesFeature,
  bulkFeature,
  sqlFeature,
  resourcesFeature,
  routingFeature,
  brokerconfigFeature,
  settingsFeature,
  eventsFeature,
  auditFeature,
  securityFeature,
  governanceFeature,
  identityLocalFeature,
  apitokensFeature,
  mcpFeature,
];
