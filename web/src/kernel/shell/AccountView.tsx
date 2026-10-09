import { Text } from '@mantine/core';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { MyRequests } from '../approvals/MyRequests.tsx';
import { useGateStatus } from '../approvals/api.ts';
import { useMe } from '../auth/api.ts';
import { useSlot } from '../slots.ts';
import classes from './Views.module.css';

/**
 * The signed-in user's own page: who you are, the approval requests you made while approvals are on
 * (what a requester comes back for, so it comes first), then what the features contribute — how to
 * change your password, the keys you hold, how to connect an assistant with one, and your display
 * preferences. Prose and forms keep a readable width; a section with a table keeps the page's.
 *
 * <p>API keys used to live under Administration, which made a per-user
 * credential look like an operator's tool and hid it from everyone without
 * {@code user:admin}. Every user has an account, so this route is ungated.
 */
export function AccountView() {
  const me = useMe();
  // Without an approval provider nothing is ever held, so the section would only ever be empty; earlier
  // requests stay on the Approvals page's Mine tab.
  const approvalsOn = useGateStatus().data?.armed === true;
  const sections = useSlot('account.sections');

  let username = <LoadingState variant="inline" label="Loading your user name" inlineSize="8rem" />;
  if (me.isError) username = <ErrorState variant="inline" error={me.error} onRetry={() => void me.refetch()} />;
  else if (me.data) username = <Text size="sm">{me.data.username}</Text>;

  return (
    <div className={classes.page}>
      <div className={classes.account}>
        <Page>
          <PageHeader
            title="Account"
            description="Who you are signed in as, and the credentials and sessions that act as you."
          />

          <Section title="Identity" description="Who you are signed in as.">
            <DescriptionList items={[{ term: 'Username', value: username }]} />
          </Section>

          {approvalsOn ? (
            <Section
              title="My requests"
              description="Operations you started that wait for, or had, a second person's approval."
            >
              <MyRequests />
            </Section>
          ) : null}

          {sections.map(({ id, title, Component }) => (
            <Section key={id} title={title ?? id}>
              <Component />
            </Section>
          ))}
        </Page>
      </div>
    </div>
  );
}
