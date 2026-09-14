--liquibase formatted sql

-- The content policy's own state (ADR-0075): masking rules and dismissal exceptions, the
-- classification inbox, and the policy version stored rows are masked under.
-- Column order follows non-negotiable #7. Never edit this file once released.

--changeset artemis-studio:platform-governance-0001-baseline splitStatements:true
CREATE TABLE governance_rule (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    address_pattern text,
    target text NOT NULL,
    selector text NOT NULL,
    data_class text NOT NULL,
    action text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    builtin boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    is_exception boolean DEFAULT false NOT NULL,
    CONSTRAINT pk_governance_rule PRIMARY KEY (id),
    CONSTRAINT ck_governance_rule_target CHECK (target IN ('HEADER', 'PROPERTY', 'BODY_PATH')),
    CONSTRAINT ck_governance_rule_class CHECK (data_class IN
        ('CREDENTIAL', 'PAN', 'IBAN', 'EMAIL', 'PHONE', 'NATIONAL_ID', 'PERSONAL')),
    CONSTRAINT ck_governance_rule_action CHECK (action IS NULL OR action IN ('DROP', 'PARTIAL', 'REDACT', 'CLEAR'))
);

-- Findings are upserted in batches by the flush job; hit_count and last_seen_at churn.
CREATE TABLE classification_finding (
    first_seen_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    hit_count bigint NOT NULL,
    address text NOT NULL,
    location text NOT NULL,
    field_path text NOT NULL,
    data_class text NOT NULL,
    status text DEFAULT 'OPEN' NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT pk_classification_finding PRIMARY KEY (id),
    CONSTRAINT uq_classification_finding UNIQUE (address, location, field_path, data_class),
    CONSTRAINT ck_classification_finding_status CHECK (status IN ('OPEN', 'CONFIRMED', 'DISMISSED'))
) WITH (fillfactor = 80, autovacuum_vacuum_scale_factor = 0.05);

CREATE TABLE governance_policy (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version integer NOT NULL,
    id smallint DEFAULT 1 NOT NULL,
    CONSTRAINT pk_governance_policy PRIMARY KEY (id),
    CONSTRAINT ck_governance_policy_single CHECK (id = 1)
);

INSERT INTO governance_policy (version) VALUES (1);

-- Built-in credential rules (data-governance spec). Fixed ids so they are recognisable everywhere.
INSERT INTO governance_rule (target, selector, data_class, id, builtin) VALUES
    ('PROPERTY', 'authorization',       'CREDENTIAL', '00000000-0000-0000-0075-000000000001', TRUE),
    ('PROPERTY', 'proxy-authorization', 'CREDENTIAL', '00000000-0000-0000-0075-000000000002', TRUE),
    ('PROPERTY', 'cookie',              'CREDENTIAL', '00000000-0000-0000-0075-000000000003', TRUE),
    ('PROPERTY', 'set-cookie',          'CREDENTIAL', '00000000-0000-0000-0075-000000000004', TRUE),
    ('PROPERTY', '*password*',          'CREDENTIAL', '00000000-0000-0000-0075-000000000005', TRUE),
    ('PROPERTY', '*secret*',            'CREDENTIAL', '00000000-0000-0000-0075-000000000006', TRUE),
    ('PROPERTY', '*token*',             'CREDENTIAL', '00000000-0000-0000-0075-000000000007', TRUE),
    ('PROPERTY', '*api-key*',           'CREDENTIAL', '00000000-0000-0000-0075-000000000008', TRUE),
    ('PROPERTY', '*api_key*',           'CREDENTIAL', '00000000-0000-0000-0075-000000000009', TRUE);

--rollback DROP TABLE IF EXISTS governance_policy;
--rollback DROP TABLE IF EXISTS classification_finding;
--rollback DROP TABLE IF EXISTS governance_rule;
