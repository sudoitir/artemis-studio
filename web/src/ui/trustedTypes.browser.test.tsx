import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { CodeHighlight, CodeHighlightAdapterProvider } from '@mantine/code-highlight';
import { MantineProvider } from '@mantine/core';

import { runLayout } from './graph/elk.ts';
import { shikiAdapter } from './codeHighlightAdapter.ts';

// The browser project is served with Studio's Trusted Types directives (vitest.config.ts), so these run
// the way a viewer's page does. An attacker who controls a message body wants markup, not text.
const PAYLOADS = [
  '<img src=x onerror=alert(1)>',
  '<script>alert(1)</script>',
  '"><svg onload=alert(2)>',
  '<iframe srcdoc="<script>alert(3)</script>"></iframe>',
  '<a href="javascript:alert(4)">x</a>',
];
const MARKUP = 'script, img, iframe, a, [onerror], [onload], [srcdoc]';

let violations: SecurityPolicyViolationEvent[];
const onViolation = (event: SecurityPolicyViolationEvent) => violations.push(event);

beforeEach(() => {
  violations = [];
  document.addEventListener('securitypolicyviolation', onViolation);
});
afterEach(async () => {
  // A violation is reported in a task of its own: let this test's arrive before the next one listens.
  await new Promise((resolve) => setTimeout(resolve));
  document.removeEventListener('securitypolicyviolation', onViolation);
});

describe('Trusted Types enforcement', () => {
  it('is on: markup written to innerHTML is refused, and text is not', () => {
    const element = document.createElement('div');

    expect(() => {
      // eslint-disable-next-line no-restricted-syntax -- The point of the test: this sink must refuse.
      element.innerHTML = PAYLOADS[0];
    }).toThrow(TypeError);
    // eslint-disable-next-line no-restricted-syntax -- A string without markup is what Mantine's CSS is.
    element.innerHTML = 'a > b { color: red }';
    expect(element.textContent).toBe('a > b { color: red }');
  });

  it('refuses a script URL and a script that nothing trusted made', () => {
    const script = document.createElement('script');

    expect(() => {
      script.src = 'https://evil.example/x.js';
    }).toThrow(TypeError);
    expect(() => new Worker('/x.js')).toThrow(TypeError);
  });
});

describe('highlighted code', () => {
  function renderCode(code: string, language: string) {
    return render(
      <MantineProvider>
        <CodeHighlightAdapterProvider adapter={shikiAdapter}>
          <CodeHighlight code={code} language={language} />
        </CodeHighlightAdapterProvider>
      </MantineProvider>,
    );
  }

  it.each(['json', 'xml', 'yaml', 'sql', 'properties', 'text'])(
    'shows markup in a %s body as inert, highlighted text',
    async (language) => {
      const code = PAYLOADS.join('\n');
      const { container } = renderCode(code, language);

      await expect.poll(() => container.querySelector('pre code span')).not.toBeNull();
      expect(container.querySelector('pre code')?.textContent).toBe(code);
      expect(container.querySelector(MARKUP)).toBeNull();
      expect(document.body.querySelector('script, img, iframe, [onerror], [onload]')).toBeNull();
      expect(violations).toEqual([]);
    },
  );
});

describe('the layout worker', () => {
  it('starts under Trusted Types, from this origin', async () => {
    const laid = await runLayout({ id: 'root', children: [{ id: 'a', width: 40, height: 20 }] });

    expect(laid.children?.[0].x).toBeTypeOf('number');
    expect(violations).toEqual([]);
  });
});
