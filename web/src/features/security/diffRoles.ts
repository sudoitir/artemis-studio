/** The permissions only one of two roles holds (authorization spec); shared ones are left out. */
export function diffRoles(a: string[], b: string[]): { onlyA: string[]; onlyB: string[] } {
  const inA = new Set(a);
  const inB = new Set(b);
  return {
    onlyA: a.filter((p) => !inB.has(p)).sort((a, b) => a.localeCompare(b)),
    onlyB: b.filter((p) => !inA.has(p)).sort((a, b) => a.localeCompare(b)),
  };
}
