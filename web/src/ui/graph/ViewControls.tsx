import { ActionIcon, Tooltip } from '@mantine/core';
import { useReactFlow, type FitViewOptions } from '@xyflow/react';
import { IconFocusCentered, IconZoomIn, IconZoomOut } from '@tabler/icons-react';

import classes from './ViewControls.module.css';

/**
 * Zoom in, zoom out and fit, for a diagram drawn with React Flow. It replaces React Flow's own
 * `<Controls>`, which brings its own colours and focus ring: these are Studio's buttons, named in words
 * and themed by the tokens. Nothing animates (`duration: 0`), so reduced motion needs nothing more.
 *
 * <p>Render it inside the `ReactFlowProvider`, in a frame that is positioned. `fit` is how the diagram
 * fits itself, so the button does what the diagram does when it first opens; `subject` names what is fitted.
 */
export function ViewControls({
  fit,
  subject = 'diagram',
}: Readonly<{
  fit: FitViewOptions;
  /** What the fit button fits, as a noun: "diagram", "topology". */
  subject?: string;
}>) {
  const flow = useReactFlow();
  const controls = [
    { label: 'Zoom in', icon: IconZoomIn, run: () => void flow.zoomIn({ duration: 0 }) },
    { label: 'Zoom out', icon: IconZoomOut, run: () => void flow.zoomOut({ duration: 0 }) },
    {
      label: `Fit the ${subject} to the view`,
      icon: IconFocusCentered,
      run: () => void flow.fitView({ ...fit, duration: 0 }),
    },
  ];
  return (
    <ActionIcon.Group orientation="vertical" className={classes.controls}>
      {controls.map((c) => (
        <Tooltip key={c.label} label={c.label} position="left" withArrow openDelay={300}>
          <ActionIcon variant="default" size="md" aria-label={c.label} onClick={c.run}>
            <c.icon size="1rem" stroke={1.75} />
          </ActionIcon>
        </Tooltip>
      ))}
    </ActionIcon.Group>
  );
}
