"""MVP-2 Diagnostic Assessment Agent (M2-T08).

Separate from :mod:`ramals_ai.diagnostic`, which proposes the next probe. This package proposes a
reading of what the evidence shows, grounded in a Spring-built context and authoritative nowhere.
"""

from ramals_ai.diagnostic_assessment.agent import DiagnosticAssessmentAgent
from ramals_ai.diagnostic_assessment.contracts import (
    Classification,
    Diagnosis,
    DiagnosticAssessmentProposal,
)

__all__ = [
    "Classification",
    "Diagnosis",
    "DiagnosticAssessmentAgent",
    "DiagnosticAssessmentProposal",
]
