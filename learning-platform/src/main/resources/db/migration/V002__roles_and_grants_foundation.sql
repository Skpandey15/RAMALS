GRANT USAGE ON SCHEMA core, ledger, audit TO ramals_core_runtime;
REVOKE CREATE ON SCHEMA core, ledger, audit FROM ramals_core_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO ramals_core_runtime;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA core TO ramals_core_runtime;
REVOKE ALL ON TABLE core.flyway_schema_history FROM ramals_core_runtime;

GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA ledger, audit TO ramals_core_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ledger, audit TO ramals_core_runtime;
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON ALL TABLES IN SCHEMA ledger, audit FROM ramals_core_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA core
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ramals_core_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA core
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ramals_core_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA ledger, audit
  GRANT SELECT, INSERT ON TABLES TO ramals_core_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA ledger, audit
  REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM ramals_core_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA ledger, audit
  GRANT USAGE, SELECT ON SEQUENCES TO ramals_core_runtime;
