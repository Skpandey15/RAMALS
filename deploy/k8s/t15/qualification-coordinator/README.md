# Qualification coordinator notes

The coordinator is deliberately a test operator helper, not an application component. It starts a
short-lived PostgreSQL client pod, obtains database-admin credentials through Secret references, and
holds a row, table, or advisory lock until the pod is stopped. A caller may supply a distinct
`CoordinatorName` when two independently releasable advisory gates are required.

Examples:

```powershell
# Hold one workflow-step row while a known database operation is observed.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 `
  -Action start -Table core.learning_workflow_step -RowId <step-uuid>

# Capture lock ownership and wait state.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 -Action inspect

# Hold a qualification-derived advisory key with an independently named helper.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 `
  -Action start -LockMode advisory -AdvisoryKey <signed-int64> `
  -CoordinatorName t15-stale-worker-gate-a

# Release the transaction and remove only the helper pod.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 -Action stop
```

The helper validates table names and UUIDs before embedding them in SQL. It does not print or
retrieve decoded Secret values. An `ACCESS EXCLUSIVE` table lock can stall the whole application;
use it only in the isolated `ramals-t15` namespace and stop it in teardown. A lock is a scheduling
instrument, not proof that the application reached a business boundary. Advisory keys must be
derived from the qualification run and paired with run-scoped PostgreSQL/application evidence.
Every crash scenario must
also capture traces/logs, PostgreSQL state, the actual pod-death event, recovery results, and the
authoritative invariant queries defined by the T15 qualification plan.
