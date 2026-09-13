import { describe, expect, it } from 'vitest';

import {
  detectPayload,
  messageTypeName,
  unavailableMessage,
  HIGHLIGHT_MAX_BYTES,
  PRETTY_MAX_BYTES,
  type PayloadInput,
} from './payload.ts';

function input(over: Partial<PayloadInput>): PayloadInput {
  return {
    body: over.body ?? null,
    bodyEncoding: over.bodyEncoding ?? 'TEXT',
    contentType: over.contentType ?? null,
    bodyTruncated: over.bodyTruncated ?? false,
    stringProperties: over.stringProperties ?? null,
  };
}

/** Base64 of an arbitrary byte sequence, for the magic-byte table. */
function b64(bytes: number[]): string {
  return btoa(String.fromCharCode(...bytes));
}

describe('declared content type', () => {
  it('wins over a body that looks like something else', () => {
    const d = detectPayload(input({ body: '<root/>', contentType: 'application/json' }));
    expect(d.format).toBe('json');
    expect(d.source).toBe('declared');
  });

  it('is read from an application property when the field is absent', () => {
    const d = detectPayload(
      input({ body: '{"a":1}', stringProperties: { _AMQ_CONTENT_TYPE: 'application/json' } }),
    );
    expect(d.format).toBe('json');
    expect(d.source).toBe('declared');
    expect(d.formatted).toBe('{\n  "a": 1\n}');
  });

  it('reports the declared format but no formatting when the body does not parse', () => {
    const d = detectPayload(input({ body: 'not json at all', contentType: 'application/json' }));
    expect(d.format).toBe('json');
    expect(d.source).toBe('declared');
    expect(d.formatted).toBeNull();
    expect(d.unavailable).toBe('unparseable');
    expect(unavailableMessage(d)).toContain('did not parse');
  });
});

describe('structural inference', () => {
  it('formats valid JSON', () => {
    const d = detectPayload(input({ body: '{"b":2,"a":[1,2]}' }));
    expect(d.format).toBe('json');
    expect(d.source).toBe('inferred');
    expect(d.formatted).toBe('{\n  "b": 2,\n  "a": [\n    1,\n    2\n  ]\n}');
    expect(d.highlightLanguage).toBe('json');
  });

  it('never claims JSON for a body that only starts like it', () => {
    const d = detectPayload(input({ body: '{ this is a log line, not json' }));
    expect(d.format).toBe('text');
    expect(d.source).toBe('none');
  });

  it('formats valid XML', () => {
    const d = detectPayload(input({ body: '<order id="7"><item>a</item></order>' }));
    expect(d.format).toBe('xml');
    expect(d.formatted).toBe('<order id="7">\n  <item>a</item>\n</order>');
    expect(d.highlightLanguage).toBe('xml');
  });

  it('does not claim XML when the parser reports a parsererror', () => {
    const d = detectPayload(input({ body: '<order><unclosed></order>' }));
    expect(d.format).toBe('text');
  });

  it('falls back to text for anything else', () => {
    expect(detectPayload(input({ body: 'plain old log line' })).format).toBe('text');
  });

  it('reports an empty body as empty', () => {
    expect(detectPayload(input({ body: '' })).format).toBe('empty');
    expect(detectPayload(input({ body: null })).format).toBe('empty');
  });
});

describe('truncated bodies', () => {
  it('says the broker truncated it, not that the body is malformed', () => {
    const d = detectPayload(
      input({ body: '{"orders":[{"id":1},{"id":2', bodyTruncated: true }),
    );
    expect(d.format).toBe('json');
    expect(d.unavailable).toBe('truncated');
    expect(unavailableMessage(d)).toContain('truncated');
    expect(unavailableMessage(d)).not.toContain('did not parse');
  });

  it('applies to a declared type too', () => {
    const d = detectPayload(
      input({ body: '{"a":', contentType: 'application/json', bodyTruncated: true }),
    );
    expect(d.unavailable).toBe('truncated');
  });

  it('a truncated body that still parses is formatted normally', () => {
    const d = detectPayload(input({ body: '{"a":1}', bodyTruncated: true }));
    expect(d.formatted).toBe('{\n  "a": 1\n}');
    expect(d.unavailable).toBeNull();
  });

  it('truncated XML reports truncation rather than "not XML"', () => {
    const d = detectPayload(input({ body: '<order><item>a</item', bodyTruncated: true }));
    expect(d.format).toBe('xml');
    expect(d.unavailable).toBe('truncated');
  });
});

describe('binary bodies', () => {
  const cases: [string, number[], string][] = [
    ['gzip', [0x1f, 0x8b, 0x08, 0x00, 0x01, 0x02], 'gzip'],
    ['zip', [0x50, 0x4b, 0x03, 0x04, 0x14, 0x00], 'zip'],
    ['Java serialized', [0xac, 0xed, 0x00, 0x05, 0x73, 0x72], 'java-serialized'],
    ['Avro', [0x4f, 0x62, 0x6a, 0x01, 0x04, 0x16], 'avro'],
  ];

  for (const [name, bytes, format] of cases) {
    it(`recognises ${name} by its magic bytes`, () => {
      const d = detectPayload(input({ body: b64(bytes), bodyEncoding: 'BASE64' }));
      expect(d.format).toBe(format);
      expect(d.source).toBe('inferred');
      expect(d.unavailable).toBe('binary');
      expect(Array.from(d.bytes!.subarray(0, bytes.length))).toEqual(bytes);
    });
  }

  it('reports an unrecognised container as plain binary, not as a guess', () => {
    const d = detectPayload(input({ body: b64([0x01, 0x02, 0x03, 0x04]), bodyEncoding: 'BASE64' }));
    expect(d.format).toBe('binary');
    expect(d.source).toBe('none');
  });

  it('never decodes binary bytes into the text body', () => {
    const d = detectPayload(input({ body: b64([0xff, 0xfe, 0x00, 0x01]), bodyEncoding: 'BASE64' }));
    expect(d.formatted).toBeNull();
    expect(d.bytes).not.toBeNull();
  });
});

describe('size ceilings', () => {
  /** JSON just over `bytes`, so the ceiling is what decides, not the content. */
  function bigJson(bytes: number): string {
    const filler = 'x'.repeat(bytes);
    return `{"pad":"${filler}"}`;
  }

  it('drops highlighting above the highlight ceiling but still formats', () => {
    const d = detectPayload(input({ body: bigJson(HIGHLIGHT_MAX_BYTES + 1) }));
    expect(d.format).toBe('json');
    expect(d.formatted).not.toBeNull();
    expect(d.highlightLanguage).toBeNull();
  });

  it('keeps highlighting below the ceiling', () => {
    const d = detectPayload(input({ body: '{"a":1}' }));
    expect(d.highlightLanguage).toBe('json');
  });

  it('drops formatting above the pretty ceiling and says why', () => {
    const d = detectPayload(input({ body: bigJson(PRETTY_MAX_BYTES + 1) }));
    expect(d.format).toBe('json');
    expect(d.formatted).toBeNull();
    expect(d.unavailable).toBe('too-large');
    expect(unavailableMessage(d)).toContain('size');
  });
});

describe('message type names', () => {
  it('maps the Artemis codes to words', () => {
    expect(messageTypeName(0)).toBe('default');
    expect(messageTypeName(3)).toBe('text');
    expect(messageTypeName(4)).toBe('bytes');
    expect(messageTypeName(5)).toBe('map');
    expect(messageTypeName(8)).toBe('large embedded');
  });

  it('shows an unknown code rather than hiding it', () => {
    expect(messageTypeName(99)).toBe('type 99');
  });
});
