import type { SubmitEvent } from 'react';
import { Stack, Text } from '@mantine/core';

import { Section } from '../../ui/Section.tsx';
import { useSettingsDraft } from './draftContext.ts';
import type { Category } from './model.ts';
import { SettingRow } from './SettingRow.tsx';
import classes from './Settings.module.css';

const WRITE_REASON = 'Changing settings needs the settings:write permission. You can read them here.';

/**
 * One category's settings as a form, under a heading per group in the order their module declares them. Only
 * the settings `shown` are listed, so a search narrows the form rather than hiding the category. Enter in any
 * field opens the review of the whole draft; only the footer's primary button applies it directly.
 */
export function CategoryForm({
  category,
  shown,
  terms,
  canWrite,
  username,
  onSubmit,
}: Readonly<{
  category: Category;
  shown: ReadonlySet<string>;
  terms: string[];
  canWrite: boolean;
  username: string | undefined;
  onSubmit: () => void;
}>) {
  const { settings } = useSettingsDraft();
  const groups: { name: string; keys: string[] }[] = [];
  for (const key of category.keys) {
    const setting = settings[key];
    if (!setting || !shown.has(key)) continue;
    const group = groups.find((g) => g.name === setting.group);
    if (group) group.keys.push(key);
    else groups.push({ name: setting.group, keys: [key] });
  }
  // One group named like its category needs no heading of its own.
  const headed = groups.length > 1 || (groups[0] && groups[0].name !== category.title);

  const submit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <form noValidate onSubmit={submit} aria-label={`${category.title} settings`}>
      <Stack gap="lg">
        <Text size="sm" c="dimmed">
          {canWrite
            ? 'Changes override the packaged defaults, are stored in the database and take effect without a restart.'
            : WRITE_REASON}
        </Text>
        {groups.map((group) => {
          const rows = group.keys.map((key) => (
            <SettingRow key={key} settingKey={key} terms={terms} canWrite={canWrite} username={username} />
          ));
          return headed ? (
            <Section key={group.name} title={group.name} headingLevel={3}>
              <div className={classes.settings}>{rows}</div>
            </Section>
          ) : (
            <div key={group.name} className={classes.settings}>
              {rows}
            </div>
          );
        })}
      </Stack>
      {/* Enter in a field submits the form, which opens the review; the visible actions are in the page's footer. */}
      <button type="submit" hidden aria-hidden tabIndex={-1} />
    </form>
  );
}
