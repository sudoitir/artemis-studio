import { useState } from 'react';
import { Collapse } from '@mantine/core';
import { CodeHighlight } from '@mantine/code-highlight';
import type { CapabilitiesView, CapabilityView } from '../api/client.ts';
import styles from './CapabilityLedger.module.css';

type Key = keyof CapabilitiesView;

const LABELS: Record<Key, string> = {
  managementRead: 'Read management data',
  managementWrite: 'Change broker state',
  notifications: 'Live events',
  messageIo: 'Message browse and send',
};

const ORDER: Key[] = [
  'managementRead',
  'managementWrite',
  'notifications',
  'messageIo',
];

/**
 * "What this connection can do", as a hanging ledger. Every row shows a status
 * word on one right-aligned column; only rows that are not plainly available
 * expand, disclosing the reason and — where a `broker.xml` change would close
 * the gap — the exact snippet to paste.
 */
export function CapabilityLedger({ capabilities }: { capabilities: CapabilitiesView }) {
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
    if (key === 'messageIo' && /degraded/i.test(cap.reason)) {
      return { text: 'Limited', tone: 'warning' };
    }
    return { text: 'Available' };
  }
  if (cap.status === 'UNKNOWN') {
    return { text: 'Needs setup', tone: 'warning' };
  }
  return { text: 'Unavailable', tone: 'danger' };
}
