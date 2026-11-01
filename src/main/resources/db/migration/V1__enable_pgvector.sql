-- Extension binaries must already be installed in the PostgreSQL container.
-- Use a migration account with CREATE EXTENSION privileges, not the runtime role.
CREATE EXTENSION IF NOT EXISTS vector;
