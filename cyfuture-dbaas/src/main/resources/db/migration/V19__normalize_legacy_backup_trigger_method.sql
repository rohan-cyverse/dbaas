-- Older application versions persisted restore safety backups with a SAFETY
-- trigger. Safety backups are manual backups in the current model, whose enum
-- accepts only MANUAL and SCHEDULED.
UPDATE backups
SET trigger_method = 'MANUAL'
WHERE trigger_method = 'SAFETY';
