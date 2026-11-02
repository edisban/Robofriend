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

DO $migrate$
DECLARE
    obj record;
    col_type text;
    col_udt text;
BEGIN
    FOR obj IN
        SELECT c.relname, c.relkind
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r' AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'a')
    LOOP
        EXECUTE format('ALTER TABLE public.%I OWNER TO %I', obj.relname, current_setting('app.role'));
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'role') THEN
        EXECUTE 'ALTER TABLE public.users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT ''USER''';
    END IF;

    EXECUTE 'ALTER TABLE public.users ADD COLUMN IF NOT EXISTS last_checkin_on DATE';
    EXECUTE 'ALTER TABLE public.memory_vault ADD COLUMN IF NOT EXISTS fact_key VARCHAR(80)';

    -- list_items may have been created by Hibernate without the cascade to users.
    IF to_regclass('public.list_items') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'list_items_user_id_fkey') THEN
        EXECUTE 'DELETE FROM public.list_items l WHERE NOT EXISTS (SELECT 1 FROM public.users u WHERE u.id = l.user_id)';
        EXECUTE 'ALTER TABLE public.list_items ADD CONSTRAINT list_items_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE';
    END IF;

    SELECT data_type, udt_name
      INTO col_type, col_udt
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'memory_vault'
       AND column_name = 'embedding';

    IF col_type = 'USER-DEFINED' AND col_udt = 'vector' THEN
        EXECUTE 'DROP INDEX IF EXISTS idx_memory_vault_embedding';
        EXECUTE 'UPDATE memory_vault SET embedding = NULL WHERE embedding IS NOT NULL AND vector_dims(embedding) <> 384';
        EXECUTE 'ALTER TABLE memory_vault ALTER COLUMN embedding TYPE text USING embedding::text';
    END IF;
END
$migrate$;

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

echo "migrate: schema and roles are up to date"
