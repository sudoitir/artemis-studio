import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useSlot } from '../slots.ts';
import classes from './Views.module.css';

/**
 * The landing page, with no cluster open: what the features contribute for it. The clusters feature
 * sends the operator to the first cluster, or teaches how to register one.
 */
export function HomeView() {
  const content = useSlot('home.empty');
  return (
    <div className={classes.page}>
      <Page>
        <PageHeader
          title="Home"
          description="Studio manages many Artemis clusters from one place. Open a cluster to see its queues, messages and metrics."
        />
        {content.map(({ id, Component }) => (
          <Component key={id} />
        ))}
      </Page>
    </div>
  );
}
