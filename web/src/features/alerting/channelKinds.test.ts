import { describe, expect, it } from 'vitest';

import {
  CHANNEL_KINDS,
  configFromFields,
  deliveryState,
  destination,
  EMPTY_FIELDS,
  fieldsFromConfig,
  generateSigningSecret,
  kindLabel,
  PAGERDUTY_ENDPOINTS,
  recipients,
  serverField,
  validateField,
  type ChannelFields,
} from './channelKinds.ts';

const ctx = (over: Partial<{ editing: boolean; hasSecret: boolean; fields: Partial<ChannelFields> }> = {}) => ({
  editing: false,
  hasSecret: false,
  ...over,
  fields: { ...EMPTY_FIELDS, ...over.fields },
});

describe('kindLabel', () => {
  it('names a known kind and echoes an unknown one', () => {
    expect(kindLabel('SLACK')).toBe('Slack');
    expect(kindLabel('CARRIER_PIGEON')).toBe('CARRIER_PIGEON');
  });
});

describe('fieldsFromConfig', () => {
  it('reads a webhook url and ignores everything else', () => {
    expect(fieldsFromConfig('WEBHOOK', '{"url":"https://x.test/hook"}')).toEqual({
      ...EMPTY_FIELDS,
      url: 'https://x.test/hook',
    });
  });

  it('falls back to the defaults for blank, malformed or non-object JSON', () => {
    expect(fieldsFromConfig('WEBHOOK', '')).toEqual(EMPTY_FIELDS);
    expect(fieldsFromConfig('WEBHOOK', '{not json')).toEqual(EMPTY_FIELDS);
    expect(fieldsFromConfig('WEBHOOK', 'null')).toEqual(EMPTY_FIELDS);
    expect(fieldsFromConfig('WEBHOOK', '{"url":42}').url).toBe('42');
    expect(fieldsFromConfig('WEBHOOK', '{"url":true}').url).toBe('true');
    expect(fieldsFromConfig('WEBHOOK', '{"url":{}}').url).toBe('');
  });

  it('leaves a kind without editable config on the defaults', () => {
    expect(fieldsFromConfig('SLACK', '{"url":"https://x.test"}')).toEqual(EMPTY_FIELDS);
  });

  it('maps a PagerDuty url to a known region, a custom receiver, or the default', () => {
    const eu = PAGERDUTY_ENDPOINTS[1].value;
    expect(fieldsFromConfig('PAGERDUTY', '{}')).toMatchObject({
      pagerDutyEndpoint: PAGERDUTY_ENDPOINTS[0].value,
      url: '',
    });
    expect(fieldsFromConfig('PAGERDUTY', JSON.stringify({ url: eu }))).toMatchObject({
      pagerDutyEndpoint: eu,
      url: '',
    });
    expect(fieldsFromConfig('PAGERDUTY', '{"url":"https://pd.internal/enqueue"}')).toMatchObject({
      pagerDutyEndpoint: 'custom',
      url: 'https://pd.internal/enqueue',
    });
  });

  it('reads an email config, joining a recipient list and defaulting port and security', () => {
    const full = fieldsFromConfig(
      'EMAIL',
      JSON.stringify({
        host: 'smtp.x.test',
        port: 465,
        security: 'TLS',
        username: 'bot',
        from: 'a@x.test',
        to: ['b@x.test', 'c@x.test'],
        subjectPrefix: '[Ops]',
      }),
    );
    expect(full).toMatchObject({
      host: 'smtp.x.test',
      port: '465',
      security: 'TLS',
      username: 'bot',
      from: 'a@x.test',
      to: 'b@x.test, c@x.test',
      subjectPrefix: '[Ops]',
    });

    const sparse = fieldsFromConfig('EMAIL', '{"security":"BOGUS","to":"d@x.test"}');
    expect(sparse).toMatchObject({ port: '587', security: 'STARTTLS', to: 'd@x.test', subjectPrefix: '' });
  });
});

describe('recipients', () => {
  it('splits on commas, semicolons and whitespace and drops blanks', () => {
    expect(recipients(' a@x.test,b@x.test; c@x.test  d@x.test ,, ')).toEqual([
      'a@x.test',
      'b@x.test',
      'c@x.test',
      'd@x.test',
    ]);
    expect(recipients('')).toEqual([]);
  });
});

describe('configFromFields', () => {
  it('trims a webhook url', () => {
    expect(JSON.parse(configFromFields('WEBHOOK', { ...EMPTY_FIELDS, url: '  https://x.test  ' }))).toEqual({
      url: 'https://x.test',
    });
  });

  it('uses the chosen PagerDuty region, or the trimmed custom url', () => {
    expect(JSON.parse(configFromFields('PAGERDUTY', EMPTY_FIELDS))).toEqual({ url: PAGERDUTY_ENDPOINTS[0].value });
    expect(
      JSON.parse(
        configFromFields('PAGERDUTY', { ...EMPTY_FIELDS, pagerDutyEndpoint: 'custom', url: ' https://pd.test ' }),
      ),
    ).toEqual({ url: 'https://pd.test' });
  });

  it('builds an email config with numeric port and null for blank optionals', () => {
    const json = configFromFields('EMAIL', {
      ...EMPTY_FIELDS,
      host: ' smtp.x.test ',
      from: ' a@x.test ',
      to: 'b@x.test; c@x.test',
      username: '  ',
      subjectPrefix: '',
    });
    expect(JSON.parse(json)).toEqual({
      host: 'smtp.x.test',
      port: 587,
      security: 'STARTTLS',
      username: null,
      from: 'a@x.test',
      to: ['b@x.test', 'c@x.test'],
      subjectPrefix: null,
    });
    expect(
      JSON.parse(configFromFields('EMAIL', { ...EMPTY_FIELDS, username: ' bot ', subjectPrefix: ' [x] ' })),
    ).toMatchObject({
      username: 'bot',
      subjectPrefix: '[x]',
    });
  });

  it('is empty for kinds whose whole config is the secret', () => {
    expect(configFromFields('SLACK', EMPTY_FIELDS)).toBe('{}');
    expect(configFromFields('TEAMS', EMPTY_FIELDS)).toBe('{}');
  });
});

describe('destination', () => {
  it('shows a webhook host, or says there is no URL', () => {
    expect(destination('WEBHOOK', '{"url":"https://hooks.x.test:8443/a"}')).toBe('hooks.x.test:8443');
    expect(destination('WEBHOOK', '{"url":"nonsense"}')).toBe('no URL');
    expect(destination('WEBHOOK', '')).toBe('no URL');
  });

  it('shows the PagerDuty region label, the custom host, or a generic custom receiver', () => {
    expect(destination('PAGERDUTY', '{}')).toBe('PagerDuty (US service region)');
    expect(destination('PAGERDUTY', JSON.stringify({ url: PAGERDUTY_ENDPOINTS[1].value }))).toBe(
      'PagerDuty (EU service region)',
    );
    expect(destination('PAGERDUTY', '{"url":"https://pd.internal/enqueue"}')).toBe('pd.internal');
    expect(destination('PAGERDUTY', '{"url":"not a url"}')).toBe('custom receiver');
  });

  it('shows the first recipient, how many more, and the server', () => {
    expect(destination('EMAIL', '{"host":"smtp.x.test","to":["a@x.test"]}')).toBe('a@x.test via smtp.x.test');
    expect(destination('EMAIL', '{"host":"smtp.x.test","to":["a@x.test","b@x.test","c@x.test"]}')).toBe(
      'a@x.test +2 via smtp.x.test',
    );
    expect(destination('EMAIL', '{"to":"a@x.test"}')).toBe('a@x.test via ?');
    expect(destination('EMAIL', '{}')).toBe('no recipients');
  });

  it('explains that Slack and Teams keep their URL as the secret', () => {
    expect(destination('SLACK', '{}')).toBe('webhook URL stored as a secret');
  });
});

describe('validateField', () => {
  it('requires a name', () => {
    expect(validateField('SLACK', 'name', '  ', ctx())).toBe('A name is required.');
    expect(validateField('SLACK', 'name', 'ops', ctx())).toBeNull();
  });

  it('requires an http(s) url for a webhook and a custom PagerDuty receiver only', () => {
    expect(validateField('WEBHOOK', 'url', 'ftp://x.test', ctx())).toBe('An http or https URL with a host.');
    expect(validateField('WEBHOOK', 'url', 'nope', ctx())).toBe('An http or https URL with a host.');
    expect(validateField('WEBHOOK', 'url', 'https://x.test', ctx())).toBeNull();
    expect(validateField('WEBHOOK', 'url', 'http://x.test', ctx())).toBeNull();
    expect(validateField('PAGERDUTY', 'url', '', ctx())).toBeNull();
    expect(validateField('PAGERDUTY', 'url', '', ctx({ fields: { pagerDutyEndpoint: 'custom' } }))).toBe(
      'An http or https URL with a host.',
    );
    expect(validateField('SLACK', 'url', '', ctx())).toBeNull();
    expect(validateField('EMAIL', 'url', '', ctx())).toBeNull();
  });

  it('requires a secret unless it is optional or already stored on an edit', () => {
    expect(validateField('SLACK', 'secret', '', ctx())).toBe('Webhook URL is required.');
    expect(validateField('SLACK', 'secret', '', ctx({ editing: true, hasSecret: true }))).toBeNull();
    expect(validateField('SLACK', 'secret', '', ctx({ editing: true, hasSecret: false }))).toBe(
      'Webhook URL is required.',
    );
    expect(validateField('EMAIL', 'secret', '', ctx())).toBeNull();
    expect(validateField('UNKNOWN', 'secret', '', ctx())).toBe('The secret is required.');
  });

  it('checks the shape of each kind of secret', () => {
    expect(validateField('SLACK', 'secret', 'nope', ctx())).toBe('An http or https URL.');
    expect(validateField('TEAMS', 'secret', 'https://x.test/wf', ctx())).toBeNull();
    expect(validateField('PAGERDUTY', 'secret', 'short', ctx())).toBe('A routing key is 32 characters; this is 5.');
    expect(validateField('PAGERDUTY', 'secret', 'k'.repeat(32), ctx())).toBeNull();
    expect(validateField('PAGERDUTY', 'secret', 'short', ctx({ fields: { pagerDutyEndpoint: 'custom' } }))).toBeNull();
    expect(validateField('EMAIL', 'secret', 'hunter2', ctx())).toBeNull();
  });

  it('accepts a signing secret that decodes to 16 bytes, prefixed or not, and rejects the rest', () => {
    const ok = btoa('0123456789abcdef');
    expect(validateField('WEBHOOK', 'secret', ok, ctx())).toBeNull();
    expect(validateField('WEBHOOK', 'secret', `whsec_${ok}`, ctx())).toBeNull();
    expect(validateField('WEBHOOK', 'secret', btoa('short'), ctx())).toBe('Must decode to at least 16 bytes.');
    expect(validateField('WEBHOOK', 'secret', '***', ctx())).toBe('Must be base64, optionally prefixed whsec_.');
  });

  it('validates the email fields, and ignores them for other kinds', () => {
    expect(validateField('EMAIL', 'host', ' ', ctx())).toBe('The SMTP server is required.');
    expect(validateField('EMAIL', 'host', 'smtp.x.test', ctx())).toBeNull();
    expect(validateField('SLACK', 'host', '', ctx())).toBeNull();

    expect(validateField('EMAIL', 'port', '587', ctx())).toBeNull();
    expect(validateField('EMAIL', 'port', '0', ctx())).toBe('A port between 1 and 65535.');
    expect(validateField('EMAIL', 'port', '70000', ctx())).toBe('A port between 1 and 65535.');
    expect(validateField('EMAIL', 'port', '2.5', ctx())).toBe('A port between 1 and 65535.');
    expect(validateField('EMAIL', 'port', 'abc', ctx())).toBe('A port between 1 and 65535.');
    expect(validateField('SLACK', 'port', 'abc', ctx())).toBeNull();

    expect(validateField('EMAIL', 'from', 'a@x.test', ctx())).toBeNull();
    expect(validateField('EMAIL', 'from', 'a@x', ctx())).toBe('A valid sender address.');
    expect(validateField('SLACK', 'from', 'nope', ctx())).toBeNull();

    expect(validateField('EMAIL', 'to', '', ctx())).toBe('At least one recipient.');
    expect(validateField('EMAIL', 'to', 'a@x.test, nope', ctx())).toBe('"nope" is not a valid address.');
    expect(validateField('EMAIL', 'to', 'a@x.test, b@x.test', ctx())).toBeNull();
    expect(validateField('SLACK', 'to', '', ctx())).toBeNull();
  });

  it('has no rule for the remaining fields', () => {
    expect(validateField('EMAIL', 'username', '', ctx())).toBeNull();
    expect(validateField('EMAIL', 'subjectPrefix', '', ctx())).toBeNull();
  });
});

describe('generateSigningSecret', () => {
  it('produces a fresh whsec_ secret of 32 random bytes that passes its own validation', () => {
    const a = generateSigningSecret();
    expect(a).toMatch(/^whsec_/);
    expect(atob(a.slice(6))).toHaveLength(32);
    expect(validateField('WEBHOOK', 'secret', a, ctx())).toBeNull();
    expect(generateSigningSecret()).not.toBe(a);
  });
});

describe('serverField', () => {
  it('extracts the field a server rejection names', () => {
    expect(serverField('to: at least one recipient')).toBe('to');
    expect(serverField('Something went wrong')).toBeNull();
  });
});

describe('deliveryState', () => {
  it('words the known states and lowercases the rest', () => {
    expect(deliveryState('PENDING')).toBe('waiting');
    expect(deliveryState('SENT')).toBe('sent');
    expect(deliveryState('DEAD')).toBe('failed');
    expect(deliveryState('FAILED')).toBe('failed');
    expect(deliveryState('RETRYING')).toBe('retrying');
  });
});

describe('CHANNEL_KINDS', () => {
  it('only SMTP may go without a secret', () => {
    expect(
      Object.entries(CHANNEL_KINDS)
        .filter(([, k]) => k.secretOptional)
        .map(([id]) => id),
    ).toEqual(['EMAIL']);
  });
});
