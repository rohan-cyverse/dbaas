ALTER TABLE databases
    DROP COLUMN IF EXISTS public_port,
    DROP COLUMN IF EXISTS access_mode,
    DROP COLUMN IF EXISTS scheduling_policy,
    DROP COLUMN IF EXISTS case_sensitive;
