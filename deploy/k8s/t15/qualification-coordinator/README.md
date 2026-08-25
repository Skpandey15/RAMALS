# Qualification coordinator notes

The coordinator is deliberately a test operator helper, not an application component. It starts a
short-lived PostgreSQL client pod, obtains database-admin credentials through Secret references, and
holds a row or table lock until the pod is stopped.

Examples:

```powershell
# Hold one workflow-step row while a known database operation is observed.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 `
  -Action start -Table core.learning_workflow_step -RowId <step-uuid>

# Capture lock ownership and wait state.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 -Action inspect

# Release the transaction and remove only the helper pod.
pwsh -File .\deploy\k8s\t15\qualification-coordinator.ps1 -Action stop
```

The helper validates table names and UUIDs before embedding them in SQL. It does not print or
retrieve decoded Secret values. An `ACCESS EXCLUSIVE` table lock can stall the whole application;
use it only in the isolated `ramals-t15` namespace and stop it in teardown. A lock is a scheduling
instrument, not proof that the application reached a business boundary. Every crash scenario must
also capture traces/logs, PostgreSQL state, the actual pod-death event, recovery results, and the
authoritative invariant queries defined by the T15 qualification plan.
