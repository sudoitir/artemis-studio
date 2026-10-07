import { Text } from '@mantine/core';

import { DescriptionList } from '../../ui/DescriptionList.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { Section } from '../../ui/Section.tsx';
import { useMe } from '../auth/api.ts';
import { useSlot } from '../slots.ts';
import classes from './Views.module.css';

/**
 * The signed-in user's own page: who you are, then what the features contribute — how to change
 * your password, the keys you hold, and how to connect an assistant with one.
 *
 * <p>API keys used to live under Administration, which made a per-user
 * credential look like an operator's tool and hid it from everyone without
 * {@code user:admin}. Every user has an account, so this route is ungated.
 */
export function AccountView() {
  const me = useMe();
  const sections = useSlot('account.sections');

  let username = <LoadingState variant="inline" label="Loading your user name" inlineSize="8rem" />;
  if (me.isError) username = <ErrorState variant="inline" error={me.error} onRetry={() => void me.refetch()} />;
  else if (me.data) username = <Text size="sm">{me.data.username}</Text>;

  return (
    <div className={classes.page}>
      <div className={classes.narrow}>
        <Page>
          <PageHeader
            title="Account"
            description="Who you are signed in as, and the credentials and sessions that act as you."
          />

          <Section title="Identity" description="Who you are signed in as.">
            <DescriptionList items={[{ term: 'Username', value: username }]} />
          </Section>

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
