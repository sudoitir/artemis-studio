/** The plain data `DiagramView` draws, and the words it reads out (ADR-0117). */

/** One box of a `DiagramView`. Everything is plain data, shown as text. */
export interface DiagramNode {
  id: string;
  /** The box's name, one line. */
  label: string;
  /** What kind of thing it is, in words ("Call a service"). Shown above the label. */
  kind?: string;
  /** A second line under the label ("inventory POST /reserve"). */
  detail?: string;
  /** Something is wrong with it: shown in words and colour, and read out with the node. */
  state?: 'error' | 'warning';
  /** Why, for a node with a state. */
  reason?: string;
}

/** One arrow of a `DiagramView`, from `source` to `target`. */
export interface DiagramEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  /** Drawn dashed: a secondary path. Its meaning belongs in its label. */
  dashed?: boolean;
}

/** What positions depend on: which nodes exist and how they connect. A new label moves nothing. */
export function layoutSignature(nodes: DiagramNode[], edges: DiagramEdge[], direction: string): string {
  return `${direction}#${nodes.map((n) => n.id).join('|')}#${edges.map((e) => `${e.source}>${e.target}`).join('|')}`;
}

/**
 * The accessible name of a node: kind, label and detail, the labels of the arrows into it (which a
 * screen reader otherwise never meets), and any problem.
 */
export function nodeName(n: DiagramNode, via: string[] = []): string {
  let name = [n.kind, n.label, n.detail].filter(Boolean).join(', ');
  if (via.length) name += `. Via ${via.join('; ')}`;
  if (n.state) name += `. ${n.state === 'error' ? 'Invalid' : 'Warning'}${n.reason ? `: ${n.reason}` : ''}`;
  return name;
}
