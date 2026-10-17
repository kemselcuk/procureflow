#!/bin/sh
set -eu

# psql variables quote values as SQL literals; never enable shell/SQL tracing here.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=migration_password="$MIGRATION_DB_PASSWORD" \
  --set=app_password="$APP_DB_PASSWORD" \
  --set=app_database="$POSTGRES_DB" \
  --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE procureflow_migrator LOGIN PASSWORD :'migration_password';
CREATE ROLE procureflow_app LOGIN PASSWORD :'app_password';
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_password';
REVOKE CONNECT ON DATABASE :"app_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_database" TO procureflow_migrator, procureflow_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO procureflow_migrator;
GRANT USAGE ON SCHEMA public TO procureflow_app;
ALTER DEFAULT PRIVILEGES FOR ROLE procureflow_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO procureflow_app;
CREATE DATABASE keycloak OWNER keycloak;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT ON DATABASE keycloak TO keycloak;
SQL
