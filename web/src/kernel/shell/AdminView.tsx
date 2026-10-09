import { ApproverQuorumNotice } from '../approvals/ApproverQuorumNotice.tsx';
import { Page } from '../../ui/Page.tsx';
import { PageHeader } from '../../ui/PageHeader.tsx';
import { ADMIN_GROUPS, useSlot } from '../slots.ts';
import { SectionNav } from './SectionNav.tsx';
import classes from './Views.module.css';

/**
 * Studio-wide administration (authorization spec): one section per contribution, such as users, roles,
 * environments and identity provider group mappings, under fixed headings — who may do what, what is
 * installed, what rules data and changes follow, then support (operator-ui spec). The open section is in
 * the URL. Each panel brings its own heading.
 */
export function AdminView() {
  const tabs = useSlot('admin.tabs');

  const groups = ADMIN_GROUPS.map((group) => ({
    ...group,
    tabs: tabs
      .filter((tab) => tab.group === group.id)
      .map(({ id, title, Component }) => ({
        id,
        title: title ?? id,
        panel: <Component />,
        preload: (Component as { preload?: () => void }).preload,
      })),
  }));

  return (
    <div className={classes.page}>
      <Page>
        <PageHeader
          title="Administration"
          description="What applies to the whole installation: who can sign in and what they may do, the environments, and what is installed."
        />
        <ApproverQuorumNotice />
        <SectionNav label="Administration sections" groups={groups} />
      </Page>
    </div>
  );
}
