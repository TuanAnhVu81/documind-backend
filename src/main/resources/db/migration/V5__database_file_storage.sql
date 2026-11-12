-- File data is kept separate from documents so interrupted uploads can be
-- recovered by the existing orphan cleanup after the safety interval.
CREATE TABLE stored_files (
    storage_key uuid PRIMARY KEY,
    content bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 10485760),
    created_at timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE database_owner name;
BEGIN
    SELECT pg_get_userbyid(datdba) INTO database_owner FROM pg_database WHERE datname = current_database();
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON stored_files TO %I', database_owner);
END $$;
