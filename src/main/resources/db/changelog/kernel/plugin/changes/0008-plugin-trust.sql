--liquibase formatted sql

-- Plugin signing and trust: the publisher keys an administrator pinned, the single-row policy that
-- says whether unverified plugins may install, and who signed each installed plugin. Trust itself
-- is computed at read time from these facts, so removing a key flags every plugin it signed
-- without touching plugin_install. Column order follows the padding rule (8-byte first, then
-- 4-byte, boolean last). Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:kernel-plugin-0008-plugin-trust
CREATE TABLE plugin_trusted_key (
    added_at timestamp with time zone NOT NULL DEFAULT now(),
    fingerprint text NOT NULL,
    name text NOT NULL,
    subject text NOT NULL,
    public_key bytea NOT NULL,
    added_by text NOT NULL
);

ALTER TABLE ONLY plugin_trusted_key
    ADD CONSTRAINT pk_plugin_trusted_key PRIMARY KEY (fingerprint);

CREATE TABLE plugin_trust_policy (
    changed_at timestamp with time zone,
    id integer NOT NULL,
    changed_by text,
    allow_unverified boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_plugin_trust_policy_single_row CHECK (id = 1)
);

ALTER TABLE ONLY plugin_trust_policy
    ADD CONSTRAINT pk_plugin_trust_policy PRIMARY KEY (id);

INSERT INTO plugin_trust_policy (id) VALUES (1);

ALTER TABLE plugin_install ADD COLUMN signer_fingerprint text;
ALTER TABLE plugin_install ADD COLUMN signer_subject text;
--rollback ALTER TABLE plugin_install DROP COLUMN IF EXISTS signer_subject;
--rollback ALTER TABLE plugin_install DROP COLUMN IF EXISTS signer_fingerprint;
--rollback DROP TABLE IF EXISTS plugin_trust_policy;
--rollback DROP TABLE IF EXISTS plugin_trusted_key;
