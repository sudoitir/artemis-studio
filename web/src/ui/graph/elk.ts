import type { ElkNode } from 'elkjs/lib/elk-api';

import elkWorkerUrl from 'elkjs/lib/elk-worker.min.js?url';

/** What an ELK instance offers, whichever way the bundler hands the CommonJS module over. */
type Elk = { layout(graph: ElkNode): Promise<ElkNode> };
type ElkConstructor = new (options?: { workerUrl?: string }) => Elk;

/**
 * elkjs is CommonJS. A bundler may hand over the constructor as the module's default or as the
 * module itself; Node hands it over as the default. Take whichever is the constructor.
 */
function constructorOf(module: unknown): ElkConstructor {
  const candidate = (module as { default?: unknown }).default ?? module;
  return candidate as ElkConstructor;
}

let elk: Promise<Elk> | null = null;

/**
 * ELK's own worker runs the layout (ADR-0080): elk-api on this thread posts to elk-worker.min.js,
 * so a large graph never blocks the page. Where there is no Worker (Node, tests), the bundled build
 * runs on this thread instead. The instance is created once; a failed layout rejects, and the canvas
 * says the graph could not be laid out.
 */
function elkInstance(): Promise<Elk> {
  elk ??=
    typeof Worker === 'undefined'
      ? import('elkjs/lib/elk.bundled.js').then((m) => new (constructorOf(m))())
      : import('elkjs/lib/elk-api.js').then((m) => new (constructorOf(m))({ workerUrl: elkWorkerUrl }));
  return elk;
}

/** Lay a graph out and return where each node goes. */
export async function runLayout(graph: ElkNode): Promise<ElkNode> {
  return (await elkInstance()).layout(graph);
}
