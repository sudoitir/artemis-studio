### Configuration keys

Startup configuration is now bound per module. Every `artemis-studio.*` key keeps its
name, with these exceptions:

- `artemis-studio.rr.clock-skew-tolerance-ms` is now
  `artemis-studio.broker.clock-skew-tolerance-ms`
  (`ARTEMIS_STUDIO_BROKER_CLOCK_SKEW_TOLERANCE_MS`). It governs every broker clock
  reading, not only request-reply tracing. Rename it if you set it.
- `artemis-studio.branding.product-name` and `artemis-studio.security.session-timeout`
  are removed. Studio never read either, so setting them had no effect; delete them
  from your configuration.
- `artemis-studio.security.oidc-default-role` (`ARTEMIS_STUDIO_SECURITY_OIDC_DEFAULT_ROLE`)
  is removed. The default role is now set per identity provider under
  Administration → Group mappings. Set it there after upgrading, or users whose
  groups match no mapping are refused sign-in.
