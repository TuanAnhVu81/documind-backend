-- Never merge or delete accounts automatically when normalizing existing V2 data.
-- Fail before changing schema so an operator can resolve ambiguous identities.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY lower(btrim(email)) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V3 cannot proceed: normalized email duplicates require manual resolution';
    END IF;
END $$;

-- Normalize only after the duplicate audit has succeeded; preserve account IDs and password hashes.
UPDATE users SET email = lower(btrim(email)) WHERE email IS DISTINCT FROM lower(btrim(email));

-- API normalizes new emails; login can also find a non-normalized V2 record.
CREATE UNIQUE INDEX uq_users_normalized_email ON users (lower(btrim(email)));
CREATE INDEX ix_refresh_family_id ON refresh_tokens (family_id);
