CREATE TABLE users (
    id uuid PRIMARY KEY, email text NOT NULL UNIQUE, password_hash text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id uuid NOT NULL, token_hash text NOT NULL UNIQUE, expires_at timestamptz NOT NULL,
    consumed_at timestamptz, revoked_at timestamptz, replaced_by uuid REFERENCES refresh_tokens(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_family ON refresh_tokens(user_id, family_id);

CREATE TABLE documents (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), filename text NOT NULL,
    storage_key text NOT NULL UNIQUE, media_type text NOT NULL, size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    status text NOT NULL CHECK (status IN ('PENDING','PROCESSING','READY','FAILED','DELETING')),
    page_count integer CHECK (page_count >= 0), paragraph_count integer CHECK (paragraph_count >= 0),
    chunk_count integer CHECK (chunk_count >= 0), error_code text, error_message text,
    created_at timestamptz NOT NULL DEFAULT now(), deleted_requested_at timestamptz,
    CONSTRAINT uq_document_owner UNIQUE(id,user_id)
);
CREATE INDEX ix_documents_owner_created ON documents(user_id,created_at);

CREATE TABLE processing_jobs (
    id uuid PRIMARY KEY, document_id uuid NOT NULL UNIQUE REFERENCES documents(id) ON DELETE CASCADE,
    status text NOT NULL CHECK (status IN ('PENDING','PROCESSING','DONE','FAILED','CANCELLED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0), next_run_at timestamptz NOT NULL DEFAULT now(),
    claimed_by text, claim_token uuid, lease_version bigint NOT NULL DEFAULT 0 CHECK (lease_version >= 0),
    lease_until timestamptz, heartbeat_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_jobs_due ON processing_jobs(next_run_at,id) WHERE status = 'PENDING';
CREATE INDEX ix_jobs_lease ON processing_jobs(lease_until,id) WHERE status = 'PROCESSING';

CREATE TABLE document_chunks (
    id uuid PRIMARY KEY, document_id uuid NOT NULL, user_id uuid NOT NULL,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0), page_number integer CHECK (page_number > 0),
    paragraph_index integer CHECK (paragraph_index > 0), start_offset integer CHECK (start_offset >= 0),
    end_offset integer, content text NOT NULL,
    embedding vector(1536) NOT NULL, embedding_model text NOT NULL, pipeline_version text NOT NULL,
    CONSTRAINT ck_chunk_offsets CHECK ((start_offset IS NULL AND end_offset IS NULL) OR
        (start_offset IS NOT NULL AND end_offset IS NOT NULL AND end_offset >= start_offset)),
    CONSTRAINT uq_chunk_index UNIQUE(document_id,chunk_index),
    CONSTRAINT fk_chunk_document_owner FOREIGN KEY(document_id,user_id)
        REFERENCES documents(id,user_id) ON DELETE CASCADE
);
CREATE INDEX ix_chunks_owner_document ON document_chunks(user_id,document_id);
CREATE INDEX ix_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE conversations (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), title text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_requested_at timestamptz, CONSTRAINT uq_conversation_owner UNIQUE(id,user_id)
);
CREATE INDEX ix_conversations_owner_updated ON conversations(user_id,updated_at);

CREATE TABLE messages (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    ordinal bigint NOT NULL CHECK (ordinal >= 0), role text NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    content text NOT NULL DEFAULT '', sources jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(sources) = 'array'),
    status text NOT NULL CHECK (status IN ('GENERATING','COMPLETE','INCOMPLETE','ERROR')),
    outcome text CHECK (outcome IN ('ANSWER','NO_EVIDENCE','CLARIFICATION_REQUIRED','CITATION_VALIDATION_FAILED',
        'SOURCE_DELETED','PROVIDER_ERROR','PERSISTENCE_ERROR','CLIENT_DISCONNECTED','TIMEOUT')),
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(conversation_id,ordinal), UNIQUE(id,conversation_id),
    CHECK (role <> 'USER' OR (status = 'COMPLETE' AND outcome IS NULL AND sources = '[]'::jsonb)),
    CHECK (status <> 'GENERATING' OR outcome IS NULL)
);
CREATE TABLE chat_generations (
    id uuid PRIMARY KEY, conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_message_id uuid NOT NULL, assistant_message_id uuid NOT NULL,
    request_key uuid NOT NULL, request_hash text NOT NULL,
    scope_mode text NOT NULL CHECK (scope_mode IN ('ALL_READY','SELECTED')),
    context_document_ids uuid[] NOT NULL DEFAULT '{}',
    status text NOT NULL CHECK (status IN ('GENERATING','COMPLETE','INCOMPLETE','ERROR')),
    outcome text CHECK (outcome IN ('ANSWER','NO_EVIDENCE','CLARIFICATION_REQUIRED','CITATION_VALIDATION_FAILED',
        'SOURCE_DELETED','PROVIDER_ERROR','PERSISTENCE_ERROR','CLIENT_DISCONNECTED','TIMEOUT')),
    heartbeat_at timestamptz, generation_version bigint NOT NULL DEFAULT 0 CHECK (generation_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(conversation_id,request_key),
    FOREIGN KEY(user_message_id,conversation_id) REFERENCES messages(id,conversation_id) ON DELETE CASCADE,
    FOREIGN KEY(assistant_message_id,conversation_id) REFERENCES messages(id,conversation_id) ON DELETE CASCADE,
    CHECK (user_message_id <> assistant_message_id), CHECK (status <> 'GENERATING' OR outcome IS NULL)
);
CREATE UNIQUE INDEX uq_generation_active ON chat_generations(conversation_id) WHERE status = 'GENERATING';

-- One durable counter event per accepted generation, including those without AI calls.
-- No FK to conversation/generation: deleting chat history must retain usage totals.
CREATE TABLE question_events (
    generation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_question_events_owner_created ON question_events(user_id,created_at);
CREATE FUNCTION record_question_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO question_events(generation_id,user_id,created_at)
        SELECT NEW.id, c.user_id, NEW.created_at FROM conversations c WHERE c.id = NEW.conversation_id;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_record_question_event AFTER INSERT ON chat_generations
    FOR EACH ROW EXECUTE FUNCTION record_question_event();

CREATE TABLE usage_logs (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),
    message_id uuid REFERENCES messages(id) ON DELETE SET NULL,
    job_id uuid REFERENCES processing_jobs(id) ON DELETE SET NULL,
    operation text NOT NULL, provider text NOT NULL, model text NOT NULL,
    prompt_tokens bigint CHECK (prompt_tokens >= 0), completion_tokens bigint CHECK (completion_tokens >= 0),
    embedding_tokens bigint CHECK (embedding_tokens >= 0), estimated_cost_usd numeric(19,9) CHECK (estimated_cost_usd >= 0),
    estimated boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_usage_owner_created ON usage_logs(user_id,created_at);

-- Retained until physical deletion succeeds. It is not the external restore journal.
CREATE TABLE document_cleanup_tasks (
    id uuid PRIMARY KEY, document_id uuid NOT NULL UNIQUE REFERENCES documents(id) ON DELETE CASCADE,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0), next_run_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(), last_error_code text
);
CREATE INDEX ix_cleanup_due ON document_cleanup_tasks(next_run_at,id);

-- Outbox survives document cleanup; an external durable journal and relay are
-- still required before a restored backup may be made accessible.
CREATE TABLE deletion_journal_outbox (
    id uuid PRIMARY KEY, document_id uuid NOT NULL UNIQUE, user_id uuid NOT NULL,
    storage_key text NOT NULL, requested_at timestamptz NOT NULL DEFAULT now(), exported_at timestamptz
);
CREATE INDEX ix_deletion_journal_unexported ON deletion_journal_outbox(requested_at) WHERE exported_at IS NULL;

-- Local setup uses a dedicated runtime role as database owner and a separate
-- migration role. Grant the owner access without hard-coding any role name.
DO $$
DECLARE database_owner name;
BEGIN
    SELECT pg_get_userbyid(datdba) INTO database_owner FROM pg_database WHERE datname = current_database();
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON users, refresh_tokens, documents, processing_jobs, document_chunks, conversations, messages, chat_generations, question_events, usage_logs, document_cleanup_tasks, deletion_journal_outbox TO %I', database_owner);
END $$;
