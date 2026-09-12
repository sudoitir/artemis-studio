import { useState } from 'react';
import { Anchor, Collapse, Text } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import { Link } from '@tanstack/react-router';
import type { CapabilitiesView, CapabilityView } from '../api/client.ts';
import styles from './CapabilityLedger.module.css';

type Key = keyof CapabilitiesView;

const LABELS: Record<Key, string> = {
  managementRead: 'Read management data',
  managementWrite: 'Change broker state',
  notifications: 'Live events',
  messageIo: 'Message browse and send',
  slowConsumerDetection: 'Slow-consumer detection',
};

/**
 * The capabilities whose snippet is — at least in part — an address or security
 * setting, which Studio can apply over the management API instead of an operator
 * editing broker.xml by hand (ADR-0068).
 *
 * The link goes to the recommendations panel rather than handing the raw snippet
 * to the XML importer: the panel seeds each entry from what the node is running,
 * which a pasted fragment cannot do, and a runtime write replaces the entry
 * rather than merging into it (notes §15 M2).
 */
const DECLARABLE: Partial<Record<Key, string>> = {
  notifications:
    'The security setting can be applied from the declared configuration; the plugin still needs broker.xml.',
  messageIo: 'This address setting can be applied from the declared configuration, no broker.xml edit needed.',
  slowConsumerDetection:
    'The address and security settings can be applied from the declared configuration; the plugin still needs broker.xml.',
};

const ORDER: Key[] = [
  'managementRead',
  'managementWrite',
  'notifications',
  'messageIo',
  'slowConsumerDetection',
];

/**
 * "What this connection can do", as a hanging ledger. Every row shows a status
 * word on one right-aligned column; only rows that are not plainly available
 * expand, disclosing the reason and — where a `broker.xml` change would close
 * the gap — the exact snippet to paste.
 */
export function CapabilityLedger({
  capabilities,
  clusterId,
}: {
  capabilities: CapabilitiesView;
  /** When the cluster is registered, snippets that are declarable link into its configuration. */
  clusterId?: string;
}) {
  const [open, setOpen] = useState<Key | null>(null);

  return (
    <div className={styles.ledger}>
      {ORDER.map((key) => {
        const cap = capabilities[key];
        const word = statusWord(key, cap);
        const expandable = word.text !== 'Available';
        const isOpen = open === key;

        return (
          <div key={key}>
            <button
              type="button"
              className={styles.row}
              data-expandable={expandable || undefined}
              aria-expanded={expandable ? isOpen : undefined}
              disabled={!expandable}
              onClick={() => expandable && setOpen(isOpen ? null : key)}
            >
              <span className={styles.label}>{LABELS[key]}</span>
              <span className={styles.status} data-tone={word.tone}>
                {word.text}
                {expandable ? (isOpen ? ' ⌃' : ' ⌄') : ''}
              </span>
            </button>

            {expandable ? (
              <Collapse expanded={isOpen}>
                <div className={styles.detail}>
                  {cap.reason}
                  {cap.brokerXmlSnippet ? (
                    <CodeHighlight
                      className={styles.snippet}
                      code={cap.brokerXmlSnippet.trimEnd()}
                      language="xml"
                    />
                  ) : null}
                  {cap.brokerXmlSnippet && clusterId && DECLARABLE[key] ? (
                    <Text size="xs" mt="xs">
                      {DECLARABLE[key]}{' '}
                      <Anchor component={Link} to={`/clusters/${clusterId}/configuration?tab=recommended`} size="xs">
                        Declare &amp; apply it
                      </Anchor>
                    </Text>
                  ) : null}
                </div>
              </Collapse>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}

function statusWord(
  key: Key,
  cap: CapabilityView,
): { text: string; tone?: 'warning' | 'danger' } {
  if (cap.status === 'AVAILABLE') {
    if (key === 'messageIo' && /degraded|truncat|Core client/i.test(cap.reason)) {
      return { text: 'Limited', tone: 'warning' };
    }
    return { text: 'Available' };
  }
  if (cap.status === 'UNKNOWN') {
    return { text: 'Needs setup', tone: 'warning' };
  }
  return { text: 'Unavailable', tone: 'danger' };
}
