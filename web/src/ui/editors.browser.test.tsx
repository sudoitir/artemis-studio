import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { userEvent } from 'vitest/browser';
import { render, screen, waitFor } from '@testing-library/react';
import { CodeHighlight, CodeHighlightAdapterProvider } from '@mantine/code-highlight';
import { MantineProvider } from '@mantine/core';

import { shikiAdapter } from './codeHighlightAdapter.ts';
import { CodeEditor } from './CodeEditor.tsx';

// The browser project is served with Studio's Trusted Types directives (vitest.config.ts). The editor and
// the code views show and edit markup that came from a broker, so what goes in must come out unchanged and
// nothing may be refused by the page's policy (ADR-0168).
const XML =
  '<core xmlns="urn:activemq:core"><!-- a & b --><name>a &amp; b</name><![CDATA[<script>alert(1)</script>]]></core>';

let violations: SecurityPolicyViolationEvent[];
const onViolation = (event: SecurityPolicyViolationEvent) => violations.push(event);

beforeEach(() => {
  violations = [];
  document.addEventListener('securitypolicyviolation', onViolation);
});
afterEach(async () => {
  await new Promise((resolve) => setTimeout(resolve));
  document.removeEventListener('securitypolicyviolation', onViolation);
});

describe('CodeEditor under Trusted Types', () => {
  it('shows a document with markup as text and gives back what is typed, unchanged', async () => {
    const onChange = vi.fn();
    const { container } = render(
      <MantineProvider>
        <CodeEditor label="Document" language="json" value={'"<img src=x onerror=alert(1)>"'} onChange={onChange} />
      </MantineProvider>,
    );

    const editor = screen.getByRole('textbox', { name: 'Document' });
    expect(editor.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(container.querySelector('img, script, [onerror]')).toBeNull();

    await userEvent.click(editor);
    await userEvent.keyboard('<b>&amp;</b>');
    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange.mock.lastCall?.[0]).toContain('<b>&amp;</b>');
    expect(violations).toEqual([]);
  });
});

describe('a message body in the code view', () => {
  it.each([
    ['XML', 'xml', XML],
    ['a script tag', 'xml', '<script>alert(1)</script><img src=x onerror=alert(1)>'],
    ['JSON with markup', 'json', '{"html":"<b>&amp;</b>","close":"</script>"}'],
  ])('shows %s as text, character for character', async (_name, language, body) => {
    const { container } = render(
      <MantineProvider>
        <CodeHighlightAdapterProvider adapter={shikiAdapter}>
          <CodeHighlight code={body} language={language} />
        </CodeHighlightAdapterProvider>
      </MantineProvider>,
    );

    await expect.poll(() => container.querySelector('code span')).not.toBeNull();
    expect(container.querySelector('code')?.textContent).toBe(body);
    expect(container.querySelector('script, img, b, name, core')).toBeNull();
    expect(violations).toEqual([]);
  });
});
