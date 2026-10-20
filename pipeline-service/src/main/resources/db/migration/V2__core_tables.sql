CREATE TABLE ingestion_batch (
    id                    uuid PRIMARY KEY,
    source_filename       text        NOT NULL,
    source_type           text        NOT NULL CHECK (source_type IN ('CSV','JSON')),
    source_checksum       char(64)    NOT NULL,
    source_size_bytes     bigint      NOT NULL,
    schema_name           text        NOT NULL,
    schema_version        text        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('RECEIVED','RUNNING','COMPLETED','FAILED','REJECTED')),
    failure_reason        text        CHECK (failure_reason IN (
                             'INVALID_FILE_FORMAT','SCHEMA_DRIFT_UNRESOLVED','INVARIANT_VIOLATION',
                             'INFRASTRUCTURE_ERROR','INTERRUPTED')),
    schema_mapping        jsonb,
    schema_drift_report   jsonb,
    read_count            integer     NOT NULL DEFAULT 0,
    direct_count          integer     NOT NULL DEFAULT 0,
    auto_corrected_count  integer     NOT NULL DEFAULT 0,
    quarantined_count     integer     NOT NULL DEFAULT 0,
    ai_calls_count        integer     NOT NULL DEFAULT 0,
    job_execution_id      bigint,
    received_at           timestamptz NOT NULL DEFAULT now(),
    started_at            timestamptz,
    finished_at           timestamptz
);
-- Un mismo contenido no puede tener dos lotes activos; los REJECTED no bloquean el reenvío (ADR-0009).
CREATE UNIQUE INDEX uq_batch_checksum_active ON ingestion_batch (source_checksum) WHERE status <> 'REJECTED';
CREATE INDEX ix_batch_status_received ON ingestion_batch (status, received_at DESC);
CREATE INDEX ix_batch_received ON ingestion_batch (received_at);

CREATE TABLE sales_transaction (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id           uuid          NOT NULL REFERENCES ingestion_batch(id),
    source_row_number  integer       NOT NULL CHECK (source_row_number >= 1),
    invoice_no         varchar(7)    NOT NULL CHECK (invoice_no ~ '^[CA]?[0-9]{6}$'),
    is_cancellation    boolean       NOT NULL,
    stock_code         varchar(20)   NOT NULL,
    description        varchar(255),
    quantity           integer       NOT NULL CHECK (quantity BETWEEN -100000 AND 100000 AND quantity <> 0),
    invoice_date       timestamp     NOT NULL CHECK (invoice_date >= TIMESTAMP '2009-01-01 00:00:00'),
    unit_price         numeric(12,2) NOT NULL CHECK (unit_price BETWEEN 0 AND 100000),
    line_total         numeric(14,2) NOT NULL,
    customer_id        varchar(5)    CHECK (customer_id ~ '^[0-9]{5}$'),
    country            varchar(64)   NOT NULL,
    load_origin        text          NOT NULL CHECK (load_origin IN ('DIRECT','AUTO_CORRECTED')),
    loaded_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_batch_row UNIQUE (batch_id, source_row_number),
    -- Defensa en profundidad (AC-13.4): BR-01/BR-02 y campos derivados
    CONSTRAINT ck_sales_cancellation CHECK (is_cancellation = (invoice_no LIKE 'C%')),
    CONSTRAINT ck_sales_br_sign CHECK ((invoice_no LIKE 'C%') = (quantity < 0)),
    CONSTRAINT ck_sales_line_total CHECK (line_total = quantity * unit_price)
);
CREATE INDEX ix_sales_origin ON sales_transaction (batch_id, load_origin);

CREATE TABLE quarantine_record (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id           uuid        NOT NULL REFERENCES ingestion_batch(id),
    source_row_number  integer     NOT NULL CHECK (source_row_number >= 1),
    raw_payload        jsonb       NOT NULL,
    violations         jsonb       NOT NULL,
    ai_analysis        jsonb,
    summary            text        NOT NULL,
    max_severity       text        CHECK (max_severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    quarantine_reason  text        NOT NULL CHECK (quarantine_reason IN (
                          'AI_REVIEW_REQUIRED','POLICY_REJECTED','VERIFICATION_FAILED','REVALIDATION_FAILED',
                          'AI_UNAVAILABLE','AI_INVALID_RESPONSE','AI_BUDGET_EXCEEDED','PROCESSING_ERROR')),
    policy_decision    jsonb,
    ai_request_id      uuid,
    status             text        NOT NULL DEFAULT 'PENDING_REVIEW'
                          CHECK (status IN ('PENDING_REVIEW','RESOLVED','DISCARDED')),
    resolution_note    text,
    resolved_at        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_quarantine_batch_row UNIQUE (batch_id, source_row_number)
);
CREATE INDEX ix_quarantine_filters ON quarantine_record (batch_id, quarantine_reason, max_severity, status);

CREATE TABLE correction_log (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id              uuid          NOT NULL,
    source_row_number     integer       NOT NULL,
    field_name            text          NOT NULL,
    error_code            text          NOT NULL,
    operation             text          NOT NULL CHECK (operation IN
                             ('TRIM','NORMALIZE_CASE','PARSE_DATE','PARSE_NUMBER','MAP_TO_ENUM','NULLIFY_TOKEN')),
    operation_params      jsonb,
    original_value        text,
    corrected_value       text,
    confidence            numeric(4,3)  NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    severity              text          NOT NULL CHECK (severity = 'LOW'),
    ai_explanation        text          NOT NULL,
    ai_provider           text          NOT NULL,
    ai_model              text          NOT NULL,
    prompt_version        text          NOT NULL,
    ai_request_id         uuid          NOT NULL,
    applied_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_correction UNIQUE (batch_id, source_row_number, field_name),
    -- Una corrección solo existe si la fila limpia existe (misma transacción de chunk)
    CONSTRAINT fk_correction_sales FOREIGN KEY (batch_id, source_row_number)
        REFERENCES sales_transaction (batch_id, source_row_number),
    CONSTRAINT ck_correction_nullify CHECK ((corrected_value IS NULL) = (operation = 'NULLIFY_TOKEN'))
);
CREATE INDEX ix_correction_batch_op ON correction_log (batch_id, operation, field_name);

CREATE TABLE ai_call_log (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id         uuid        NOT NULL,
    batch_id           uuid        NOT NULL REFERENCES ingestion_batch(id),
    source_row_number  integer,
    endpoint           text        NOT NULL CHECK (endpoint IN ('TRIAGE','SCHEMA_DRIFT')),
    outcome            text        NOT NULL CHECK (outcome IN
                          ('SUCCESS','CACHE_HIT','TIMEOUT','UNAVAILABLE','CIRCUIT_OPEN','INVALID_RESPONSE','HTTP_ERROR')),
    http_status        integer,
    error_code         text,
    attempts           smallint    NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    llm_calls          smallint,
    latency_ms         integer,
    provider           text,
    model              text,
    prompt_version     text,
    input_tokens       integer,
    output_tokens      integer,
    error_detail       text,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_call_batch ON ai_call_log (batch_id, outcome);
