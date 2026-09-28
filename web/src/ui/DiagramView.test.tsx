import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';

import { DiagramView } from './DiagramView.tsx';
import { layoutSignature, nodeName, type DiagramEdge, type DiagramNode } from './diagram.ts';

const nodes: DiagramNode[] = [
  { id: 'q', kind: 'Queue', label: 'orders' },
  { id: 'a', kind: 'Call a service', label: 'reserve', detail: 'inventory POST /reserve' },
  { id: 'b', kind: 'Call a service', label: 'charge', state: 'error', reason: 'Choose a connection.' },
  { id: 'u', kind: 'Undo', label: 'release' },
];
const edges: DiagramEdge[] = [
  { id: 'q-a', source: 'q', target: 'a' },
  { id: 'a-b', source: 'a', target: 'b' },
  { id: 'a-u', source: 'a', target: 'u', label: 'undo 1', dashed: true },
];

function draw(onSelect = vi.fn(), selectedId: string | null = null) {
  render(
    <MantineProvider>
      <DiagramView nodes={nodes} edges={edges} selectedId={selectedId} onSelect={onSelect} aria-label="Flow orders" />
    </MantineProvider>,
  );
  return onSelect;
}

describe('DiagramView', () => {
  it('names each box with its kind, label and problem in words', async () => {
    draw();
    expect(await screen.findByRole('button', { name: 'Call a service, charge. Invalid: Choose a connection.' })).toBeInTheDocument();
    expect(screen.getByText('Invalid')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Flow orders' })).toBeInTheDocument();
    // An arrow's label reaches a screen reader through the box it points at.
    expect(screen.getByRole('button', { name: 'Undo, release. Via undo 1' })).toBeInTheDocument();
  });

  it('is one tab stop, moves with the arrow keys and selects with Enter', async () => {
    const onSelect = draw();
    const user = userEvent.setup();
    await screen.findByRole('button', { name: /^Queue, orders/ });
    await waitFor(() => expect(screen.getAllByRole('button', { name: /^(Queue|Call|Undo)/ }).filter((b) => b.tabIndex === 0)).toHaveLength(1));
    await user.tab();
    expect(screen.getByRole('button', { name: /^Queue, orders/ })).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('button', { name: /reserve/ })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onSelect).toHaveBeenCalledWith('a');
    expect(screen.getByText(/^Selected Call a service, reserve/)).toBeInTheDocument();
  });

  it('marks the selected box as pressed', async () => {
    draw(vi.fn(), 'b');
    expect(await screen.findByRole('button', { name: /charge/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('keeps its layout when only words change', () => {
    const renamed = nodes.map((n) => ({ ...n, label: `${n.label}!`, state: undefined }));
    expect(layoutSignature(renamed, edges, 'DOWN')).toBe(layoutSignature(nodes, edges, 'DOWN'));
    expect(layoutSignature(nodes, edges.slice(1), 'DOWN')).not.toBe(layoutSignature(nodes, edges, 'DOWN'));
  });

  it('reads a warning as a warning', () => {
    expect(nodeName({ id: 'x', label: 'x', state: 'warning', reason: 'Slow.' })).toBe('x. Warning: Slow.');
  });
});
