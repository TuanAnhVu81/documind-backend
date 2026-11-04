-- Identifies the storage volume belonging to this database. The matching
-- marker must live on the persistent volume, not in the application image.
CREATE TABLE document_storage_identity (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    storage_id uuid NOT NULL
);
INSERT INTO document_storage_identity(singleton, storage_id) VALUES (true, gen_random_uuid());

DO $$
DECLARE database_owner name;
BEGIN
    SELECT pg_get_userbyid(datdba) INTO database_owner FROM pg_database WHERE datname = current_database();
    EXECUTE format('GRANT SELECT ON document_storage_identity TO %I', database_owner);
END $$;
