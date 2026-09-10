"""M2-ADR-032 step 3: the bounded advisory diagnostic-probe recommendation reasoner.

Reads governed H6/H7 evidence through the existing MCP-3 read client, asks the model for exactly one
next diagnostic-probe candidate (``contracts/mvp2/diagnostic-probe-proposal.v1.schema.json``), and
returns it in an ``AIProposalEnvelope``. It asserts nothing about the learner: no confidence, no
ranking, no diagnosis, no probe selection or execution. Java's ``DiagnosticProbeProposalGate``
independently decides whether the recommendation has any effect, and step 3 consumes an accepted
recommendation nowhere.
"""

from __future__ import annotations

from ramals_ai.diagnostic_probe.reasoner import DiagnosticProbeReasoner

__all__ = ["DiagnosticProbeReasoner"]
