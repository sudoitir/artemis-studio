import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { useSlot } from '../slots.ts';
import classes from './Views.module.css';

/**
 * The landing page, with no cluster open: what the features contribute for it. The clusters feature
 * sends the operator to the first cluster, so the page is seen only while that is decided or while no
 * cluster is registered, which is what it is titled for.
 */
export function HomeView() {
  const content = useSlot('home.empty');
  return (
    <div className={classes.page}>
      <Page>
        <PageHeader
          title="Clusters"
          description="The first registered cluster opens from here. Switch between clusters at the top of the sidebar."
        />
        {content.map(({ id, Component }) => (
          <Component key={id} />
        ))}
      </Page>
    </div>
  );
}
