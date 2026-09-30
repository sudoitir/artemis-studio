# @artemis-studio/client

A typed client for the [Artemis Studio](https://github.com/sudoitir/artemis-studio) API. Its types are
generated from the OpenAPI document of the same release, so version `2026.9.3` of the client describes
Studio `2026.09.3`.

```ts
import { createStudioClient, ProblemError } from '@artemis-studio/client';

const studio = createStudioClient({ baseUrl: 'https://studio.example.com', token: process.env.STUDIO_TOKEN! });

try {
  const { data } = await studio.GET('/api/v1/clusters');
} catch (error) {
  // Every non-2xx answer is an RFC 9457 problem; `type` is a stable URI.
  if (error instanceof ProblemError) console.error(error.status, error.type, error.message);
}
```

Requests are made with [openapi-fetch](https://openapi-ts.dev/openapi-fetch/), so its options and
middleware apply.
