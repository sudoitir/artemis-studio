import { useSlot } from '../slots.ts';

/**
 * The landing page, with no cluster open: what the features contribute for it. The clusters feature
 * sends the operator to the first cluster, or teaches how to register one.
 */
export function HomeView() {
  const content = useSlot('home.empty');
  return (
    <>
      {content.map(({ id, Component }) => (
        <Component key={id} />
      ))}
    </>
  );
}
