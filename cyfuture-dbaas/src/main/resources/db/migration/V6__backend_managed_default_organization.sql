-- Clients cannot create or select an organization. Seed the immutable
-- backend-managed default, then preserve existing projects by moving them
-- into that one backend-controlled organization.
INSERT IGNORE INTO organizations (organization_id, display_name, description, status, created_at, updated_at)
VALUES ('org-000000000000', 'quiet-harbor', 'Backend-managed default organization',
        'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

UPDATE projects
SET organization_id = 'org-000000000000'
WHERE organization_id <> 'org-000000000000';
