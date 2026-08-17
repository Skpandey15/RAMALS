"""Tutor Agent V1 (M1-T07).

Proposes explanations, hints and checks for understanding. Holds no authority: the deterministic
engines in Spring decide what a learner has mastered.
"""

from ramals_ai.tutor.agent import TutorAgent
from ramals_ai.tutor.minimizer import minimize
from ramals_ai.tutor.validation import validate

__all__ = ["TutorAgent", "minimize", "validate"]
