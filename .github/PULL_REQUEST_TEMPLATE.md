<!-- One logical change per PR. -->

## What and why

<!-- What changes, and the reason. Link the issue. -->

Closes #

## Checklist

- [ ] `just verify` is green
- [ ] Behaviour change went through OpenSpec (`openspec/changes/...`), or this is
      a bug fix / refactor that doesn't need one
- [ ] Touches an ADR decision? A new ADR is included
- [ ] New behaviour has a test at the user's altitude
- [ ] New Liquibase changeset (no edits to released ones)
- [ ] No raw colour/spacing literals in frontend components; logical CSS
      properties only
- [ ] Product name not hard-coded outside `Branding.java` / `web/src/branding.ts`
