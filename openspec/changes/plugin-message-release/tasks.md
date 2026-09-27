## 1. Release a consumed message without spending a delivery attempt

- [ ] 1.1 `Disposition.RELEASE` with its Javadoc (`@PluginApi`); plugin consumer sessions use `INDIVIDUAL_ACKNOWLEDGE`; `PluginDrains` settles a release by rolling the Core session back without considering the message delivered (`ClientSession.rollback(false)` through the pooled session's internal session); a tap discards it
- [ ] 1.2 Integration test against a real broker with a low `max-delivery-attempts`: releasing more often than the limit keeps the message on its queue with an unchanged delivery count; rejecting as often as the limit dead-letters it
- [ ] 1.3 Plugin guide: when to release and when to reject, and releasing while stopping the registration
- [ ] 1.4 `just verify`; japicmp reports the change as compatible; branch, PR, green CI, merge, release; archive this change
