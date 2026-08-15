CREATE SCHEMA IF NOT EXISTS core AUTHORIZATION ramals_core_migration;
CREATE SCHEMA IF NOT EXISTS ledger AUTHORIZATION ramals_core_migration;
CREATE SCHEMA IF NOT EXISTS audit AUTHORIZATION ramals_core_migration;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA core, ledger, audit FROM PUBLIC;

COMMENT ON SCHEMA core IS 'Mutable authoritative RAMALS domain state';
COMMENT ON SCHEMA ledger IS 'Immutable learning provenance';
COMMENT ON SCHEMA audit IS 'Immutable security and privileged-operation audit';
