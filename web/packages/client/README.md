# @rahdar/client

A typed client for the [Rahdar](https://github.com/sudoitir/rahdar) API. Its types are
generated from the OpenAPI document of the same release, so version `2026.9.3` of the client describes
Rahdar `2026.09.3`.

```ts
import { createRahdarClient, ProblemError } from '@rahdar/client';

const rahdar = createRahdarClient({ baseUrl: 'https://rahdar.example.com', token: process.env.RAHDAR_TOKEN! });

try {
  const { data } = await rahdar.GET('/api/v1/clusters');
} catch (error) {
  // Every non-2xx answer is an RFC 9457 problem; `type` is a stable URI.
  if (error instanceof ProblemError) console.error(error.status, error.type, error.message);
}
```

Requests are made with [openapi-fetch](https://openapi-ts.dev/openapi-fetch/), so its options and
middleware apply.
