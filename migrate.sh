#!/bin/sh

set -eu

APP_USER="${APP_DB_USER:-botuser}"
APP_PASSWORD="${APP_DB_PASSWORD:-botpass}"
DB_NAME="${POSTGRES_DB:-botdb}"
LEGACY_SUPERUSER="${LEGACY_DB_SUPERUSER:-postgres}"
LEGACY_PASSWORD="${LEGACY_DB_PASSWORD:-password}"

case "$APP_USER" in
    [A-Za-z_][A-Za-z0-9_]*) ;;
    *) echo "migrate: invalid APP_DB_USER '$APP_USER'" >&2; exit 1 ;;
esac

run_as_superuser() {
    if PGPASSWORD="$LEGACY_PASSWORD" psql -U "$LEGACY_SUPERUSER" -d "$DB_NAME" -tAc 'SELECT 1' >/dev/null 2>&1; then
        PGPASSWORD="$LEGACY_PASSWORD" psql -U "$LEGACY_SUPERUSER" -d "$DB_NAME" "$@"
    else
        PGPASSWORD="$APP_PASSWORD" psql -U "$APP_USER" -d "$DB_NAME" "$@"
    fi
}

cat > /tmp/migration.sql <<'SQL'
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')
\gexec

SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'app_user', :'app_password')
\gexec

SELECT set_config('app.role', :'app_user', false);

-- Extensions need superuser rights, which the app role may not have; the
-- backend's Flyway migrations rely on this one being present.
CREATE EXTENSION IF NOT EXISTS vector;

-- Table structure is managed by the backend's Flyway migrations
-- (bot-back/src/main/resources/db/migration). This script only handles the
-- database role, its password, and table ownership for databases created by
-- an older setup that used a different superuser.
DO $owner$
DECLARE
    obj record;
BEGIN
    FOR obj IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r' AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'a')
    LOOP
        EXECUTE format('ALTER TABLE public.%I OWNER TO %I', obj.relname, current_setting('app.role'));
    END LOOP;
END
$owner$;

DO $grants$
DECLARE
    obj record;
BEGIN
    FOR obj IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO %I', obj.tablename, current_setting('app.role'));
    END LOOP;

    FOR obj IN SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' LOOP
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE public.%I TO %I', obj.sequencename, current_setting('app.role'));
    END LOOP;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), current_setting('app.role'));
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', current_setting('app.role'));
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I', current_setting('app.role'));
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I', current_setting('app.role'));
END
$grants$;
SQL

run_as_superuser -v ON_ERROR_STOP=1 -v app_user="$APP_USER" -v app_password="$APP_PASSWORD" -f /tmp/migration.sql

echo "migrate: database role and permissions are up to date"
