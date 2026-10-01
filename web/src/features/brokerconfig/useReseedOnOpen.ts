import { useEffect, useRef } from 'react';
import type { UseFormReturnType } from '@mantine/form';

/**
 * Starts an editor's form over each time its drawer opens, or opens on another item, and leaves it
 * alone while the drawer closes so what it showed does not change under the reader. The form and
 * `seed` are read through a ref: `useForm` hands out new functions every render, and depending on
 * them would reseed on every render.
 */
export function useReseedOnOpen<Values, Source>(
  form: UseFormReturnType<Values>,
  opened: boolean,
  source: Source,
  seed: (source: Source) => Values,
) {
  const latest = useRef({ form, seed });
  useEffect(() => {
    latest.current = { form, seed };
  });
  useEffect(() => {
    if (!opened) return;
    const { form: current, seed: make } = latest.current;
    const values = make(source);
    current.setValues(values);
    current.resetDirty(values);
    current.clearErrors();
  }, [opened, source]);
}
