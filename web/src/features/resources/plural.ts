/** The plural of a resource noun as the list views write it: "address" is "addresses", not "addresss". */
export function plural(noun: string): string {
  return noun.endsWith('s') ? `${noun}es` : `${noun}s`;
}
