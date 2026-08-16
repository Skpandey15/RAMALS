-- MVP-1 entry criterion 6: the AI workload must not reach core or ledger directly, and must not be
-- the authority on mastery or progression.
--
-- Build the constraint before the thing it constrains. MVP-1 introduces a Python runtime; this
-- denies its database identity everything, so the boundary is an enforceable privilege on the day
-- the first line of that service is written rather than a sentence in a design document. If the AI
-- service needs learner context it asks the platform API under its own authorisation, where
-- ownership rules and audit apply.
--
-- The role itself is NOT created here. `ramals_core_migration` deliberately lacks CREATEROLE — a
-- migration that can mint roles is a privilege-escalation path — so `ramals_ai_runtime` is
-- provisioned alongside the other roles in infrastructure/docker/postgres-init, and this migration
-- only removes privilege. Where the role has not been provisioned the block below is a no-op, and
-- AiRuntimeBoundaryIntegrationTests asserts the resulting posture either way.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
    RAISE NOTICE 'ramals_ai_runtime is not provisioned here; skipping AI boundary revokes';
    RETURN;
  END IF;

  -- Without USAGE on the schema every object inside it is unreachable, whatever table grants exist.
  -- The table-level revokes are belt and braces: they make the intent explicit and survive someone
  -- later granting schema USAGE without thinking through the consequences.
  EXECUTE 'REVOKE ALL ON SCHEMA core, ledger, audit FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON ALL TABLES IN SCHEMA core, ledger, audit FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON ALL SEQUENCES IN SCHEMA core, ledger, audit FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON ALL FUNCTIONS IN SCHEMA core, ledger, audit FROM ramals_ai_runtime';

  -- Tables created by future migrations must not leak to this role either. Without these, a table
  -- added in MVP-1 could silently become readable by the AI identity.
  EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA core, ledger, audit '
          'REVOKE ALL ON TABLES FROM ramals_ai_runtime';
  EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA core, ledger, audit '
          'REVOKE ALL ON SEQUENCES FROM ramals_ai_runtime';
  EXECUTE 'ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA core, ledger, audit '
          'REVOKE ALL ON FUNCTIONS FROM ramals_ai_runtime';

  -- Deny the connection itself. Privilege denial inside the database is the second line; not being
  -- able to open a session at all is the first.
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM ramals_ai_runtime', current_database());
END
$$;
