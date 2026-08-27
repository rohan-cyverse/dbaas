ALTER TABLE databases ADD COLUMN public_port INTEGER;

CREATE UNIQUE INDEX uk_databases_public_port
    ON databases(public_port)
    WHERE public_port IS NOT NULL;
