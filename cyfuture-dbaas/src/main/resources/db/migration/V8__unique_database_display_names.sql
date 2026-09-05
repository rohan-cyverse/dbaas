-- A database display name is the human-facing handle shown in the UI. Keep it
-- unique inside a project while immutable database_id remains authoritative.
ALTER TABLE database_instances
    ADD CONSTRAINT uk_database_project_display_name
    UNIQUE (project_name, display_name);
