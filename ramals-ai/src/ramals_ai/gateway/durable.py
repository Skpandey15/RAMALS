"""Contract B admission: the one place a durable execution is refused.

This module is the fail-closed half of M2-ADR-016 §4 -- *"an adapter that cannot honour Contract B
must declare it unsupported and fail. It must never fall through to a Contract A style call, and it
must never redispatch to recover from its own inability to reconcile."*

There is deliberately no durable execution path here yet. The guard exists before the capability
does, because the failure it prevents is specific and quiet: a durable execution that completes as
an ordinary synchronous submission leaves a row that *looks* recoverable, carries no provider
execution identity, and will be treated by a later recovery worker as retrievable when it is not.
That is strictly worse than refusing -- Contract A's honesty about INDETERMINATE is its most
valuable property, and a silent degradation launders an unrecoverable execution into one that
appears recoverable.

Nothing in this module contacts a provider. A refusal costs no tokens and no network call, which is
the point: the request is rejected on a published capability, not on a failed attempt.
"""

from __future__ import annotations

import logging

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import (
    DurableExecutionCapability,
    resolve_durable_capability,
)
from ramals_ai.telemetry.logging import business_event

logger = logging.getLogger(__name__)


def require_durable_execution_support(adapter: object) -> DurableExecutionCapability:
    """Returns the adapter's capability, or raises ``CONTRACT_B_UNSUPPORTED``.

    The only sanctioned entry point to a Contract B execution. Everything downstream of it may
    assume durable execution is actually available; anything that bypasses it is bypassing the
    decision M2-ADR-016 made.

    Raises rather than returning a falsy capability on purpose. A caller can ignore a return value,
    and the one failure mode worth engineering against here is a caller that forgets to check.
    """
    capability = resolve_durable_capability(adapter)
    if capability.supported:
        return capability

    name = getattr(adapter, "name", "unknown")
    reason = capability.reason or "adapter declares no durable recoverable execution capability"

    # Logged as a business event rather than a warning-with-a-string: an operator asking why a
    # Contract B route refused needs the row that failed, not a sentence about it.
    business_event(
        logger,
        level=logging.ERROR,
        operation="gateway.durable.unsupported",
        message="refused a durable execution on an adapter that cannot honour Contract B",
        fields={
            "adapter": name,
            "errorCode": GatewayErrorCode.CONTRACT_B_UNSUPPORTED.value,
            "reason": reason,
            "replaySafeAdmission": capability.replay_safe_admission,
            "durableExecutionId": capability.durable_execution_id,
            "statusLookup": capability.status_lookup,
            "resultRetrieval": capability.result_retrieval,
            "resultRetentionDays": capability.result_retention_days,
            "outcome": "REFUSED",
        },
    )
    raise GatewayError(
        GatewayErrorCode.CONTRACT_B_UNSUPPORTED,
        f"adapter '{name}' cannot honour durable recoverable execution: {reason}",
    )


__all__ = ["require_durable_execution_support"]
