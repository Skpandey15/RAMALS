"""Adaptation Agent V1: bounded, non-authoritative next-action proposals."""

from ramals_ai.adaptation.agent import AdaptationAgent
from ramals_ai.adaptation.minimizer import minimize
from ramals_ai.adaptation.validation import validate

__all__ = ["AdaptationAgent", "minimize", "validate"]
