import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';

/**
 * The lint rules that keep strings from becoming markup or code (ADR-0168) fire on the code they are
 * for. A fixture is linted as a source file, so the app's own config is the one that is exercised.
 */
const eslint = new ESLint({ overrideConfigFile: 'eslint.config.js' });

async function rulesFiredBy(code: string, filePath = 'src/ui/fixture.tsx'): Promise<string[]> {
  const [result] = await eslint.lintText(code, { filePath });
  return result.messages.map((message) => message.ruleId ?? 'parse-error');
}

describe('XSS lint guards', () => {
  it.each([
    [
      'dangerouslySetInnerHTML as a prop',
      'export const A = (p: { h: string }) => <div dangerouslySetInnerHTML={{ __html: p.h }} />;',
    ],
    ['dangerouslySetInnerHTML in spread props', 'export const props = { dangerouslySetInnerHTML: { __html: "x" } };'],
    ['innerHTML assignment', 'export const f = (el: HTMLElement, h: string) => { el.innerHTML = h; };'],
    ['outerHTML assignment', 'export const f = (el: HTMLElement, h: string) => { el.outerHTML = h; };'],
    [
      'insertAdjacentHTML',
      'export const f = (el: HTMLElement, h: string) => { el.insertAdjacentHTML("beforeend", h); };',
    ],
    ['document.write', 'export const f = (h: string) => { document.write(h); };'],
  ])('refuses %s', async (_name, code) => {
    expect(await rulesFiredBy(code)).toContain('no-restricted-syntax');
  });

  it.each([
    ['eval', 'export const f = (s: string) => eval(s);', 'no-eval'],
    ['new Function', 'export const f = (s: string) => new Function(s);', 'no-new-func'],
    ['a string passed to setTimeout', 'export const f = () => setTimeout("go()", 10);', 'no-implied-eval'],
    ['a string passed to setInterval', 'export const f = () => setInterval("go()", 10);', 'no-implied-eval'],
    ['a javascript: URL', 'export const url = "javascript:alert(1)";', 'no-script-url'],
  ])('refuses %s', async (_name, code, rule) => {
    expect(await rulesFiredBy(code)).toContain(rule);
  });

  it('accepts what the code does instead', async () => {
    const code = `
      export const f = (el: HTMLElement, text: string) => { el.textContent = text; };
      export const g = () => setTimeout(() => undefined, 10);
    `;
    expect(await rulesFiredBy(code)).toEqual([]);
  });

  it('lets a test carry an attack payload, and nothing else', async () => {
    expect(await rulesFiredBy('export const payload = "javascript:alert(1)";', 'src/ui/fixture.test.ts')).toEqual([]);
    expect(
      await rulesFiredBy('export const f = (el: HTMLElement) => { el.innerHTML = "x"; };', 'src/ui/fixture.test.ts'),
    ).toContain('no-restricted-syntax');
  });
});
