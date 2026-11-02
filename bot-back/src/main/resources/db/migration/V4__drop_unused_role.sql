-- users.role was never read: there are no roles in the app.
ALTER TABLE users DROP COLUMN IF EXISTS role;
