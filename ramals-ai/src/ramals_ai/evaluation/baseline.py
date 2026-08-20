"""Approved baselines and the regression rule (M1-T15, M1-ADR-009).

A baseline is the reference point a later run is compared against, so its failure mode is not being
wrong — it is being *movable*. Everything here exists to stop the reference point drifting:

* a baseline is identified by what produced it (agent, prompt, route, dataset version), so a
  comparison can state what it is comparing;
* it is **approved by a named person**, never by being recent, best, or the only one available;
* it is **append-only** — re-approval supersedes and does not overwrite, for the same reason MVP-0
  keeps superseded engine identifiers;
* a **hard-gate regression can never be accepted**, and a quality regression only with a named
  owner, a scope and an expiry.

The quality half cannot be exercised on ``ci-fake``, which returns a canned string. That is why
:class:`Comparison` distinguishes *no regression* from *not measured* rather than collapsing them:
an unmeasured dimension compared as zero would report a catastrophic regression, and compared as
"fine" would hide a real one.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

from ramals_ai.evaluation.harness import UNMEASURED

REGRESSION_LIMIT = Decimal("0.05")
"""Doc 07 §2. Absolute drop on the normalized 0.00-1.00 scale, so this is five percentage points."""


class BaselineError(RuntimeError):
    """A baseline was malformed, unapproved, or asked to do something it must not."""


@dataclass(frozen=True)
class BaselineIdentity:
    """What produced a result. M1-ADR-009 identifies an approved baseline by exactly this."""

    agent: str
    agent_version: str
    prompt_version: str
    model_route: str
    dataset_version: str

    def comparable_to(self, other: BaselineIdentity) -> bool:
        """Whether two results are about the same thing.

        Dataset version is part of it. A result scored against a different dataset is not a
        regression check — it is two different measurements — and reporting it as a pass is how a
        dataset edit gets read as a quality improvement.
        """
        return self.agent == other.agent and self.dataset_version == other.dataset_version


@dataclass(frozen=True)
class Approval:
    """Who approved a baseline. Not optional: an unapproved record is not a baseline."""

    approved_by: str
    approved_on: date

    @staticmethod
    def parse(raw: dict[str, Any]) -> Approval:
        approver = str(raw.get("approvedBy", "")).strip()
        if not approver:
            # The whole point of approval is that a person looked. A blank name is a record that
            # somebody ran something, which is what "recent" already tells you.
            raise BaselineError("a baseline must name the person who approved it")
        return Approval(approved_by=approver, approved_on=date.fromisoformat(raw["approvedOn"]))


@dataclass(frozen=True)
class RegressionAcceptance:
    """A written acceptance of a quality regression, in M1-ADR-000's shape."""

    owner: str
    scope: str
    expires_on: date

    def valid_on(self, today: date) -> bool:
        return today <= self.expires_on

    @staticmethod
    def parse(raw: dict[str, Any]) -> RegressionAcceptance:
        owner = str(raw.get("owner", "")).strip()
        scope = str(raw.get("scope", "")).strip()
        expiry = str(raw.get("expiresOn", "")).strip()
        if not owner or not scope or not expiry:
            # All three or none. An acceptance missing any of them is an acceptance nobody owns, or
            # one that never lapses, and both are how a temporary exception becomes permanent.
            raise BaselineError(
                "a regression acceptance needs a named owner, a scope and an expiry"
            )
        return RegressionAcceptance(owner=owner, scope=scope, expires_on=date.fromisoformat(expiry))


@dataclass(frozen=True)
class Baseline:
    """An approved evaluation result."""

    identity: BaselineIdentity
    approval: Approval
    hard_gate_passed: bool
    quality: dict[str, str]
    superseded_by: str | None = None

    def score(self, dimension: str) -> Decimal | None:
        """The recorded score, or ``None`` when the dimension was never measured."""
        raw = self.quality.get(dimension, UNMEASURED)
        return None if raw == UNMEASURED else Decimal(raw)


@dataclass(frozen=True)
class DimensionComparison:
    """One quality dimension, compared."""

    dimension: str
    baseline: Decimal | None
    candidate: Decimal | None

    @property
    def measured(self) -> bool:
        return self.baseline is not None and self.candidate is not None

    @property
    def drop(self) -> Decimal | None:
        if self.baseline is None or self.candidate is None:
            return None
        return self.baseline - self.candidate

    @property
    def regressed(self) -> bool:
        """Beyond the limit, and only when both sides were actually measured."""
        drop = self.drop
        return drop is not None and drop > REGRESSION_LIMIT


@dataclass(frozen=True)
class Comparison:
    """A candidate result against an approved baseline."""

    identity: BaselineIdentity
    hard_gate_passed: bool
    baseline_hard_gate_passed: bool
    dimensions: tuple[DimensionComparison, ...]
    acceptance: RegressionAcceptance | None = None
    today: date = field(default_factory=date.today)

    @property
    def hard_gate_regressed(self) -> bool:
        """The gate went from passing to failing. Never acceptable, at any seniority."""
        return self.baseline_hard_gate_passed and not self.hard_gate_passed

    @property
    def quality_regressions(self) -> tuple[DimensionComparison, ...]:
        return tuple(dimension for dimension in self.dimensions if dimension.regressed)

    @property
    def unmeasured(self) -> tuple[str, ...]:
        """Dimensions no comparison could be made for.

        Reported, never silently treated as passing.
        """
        return tuple(dimension.dimension for dimension in self.dimensions if not dimension.measured)

    def blocks_release(self) -> bool:
        """Whether this result may become a release candidate."""
        if not self.hard_gate_passed or self.hard_gate_regressed:
            # No acceptance path. Zero tolerated means zero: there is no owner senior enough to
            # approve a cross-learner leak.
            return True
        if not self.quality_regressions:
            return False
        if self.acceptance is None:
            return True
        return not self.acceptance.valid_on(self.today)

    def reasons(self) -> tuple[str, ...]:
        """Why it blocks, in terms an operator can act on."""
        reasons: list[str] = []
        if not self.hard_gate_passed:
            reasons.append("hard gate failed")
        if self.hard_gate_regressed:
            reasons.append("hard-gate regression, which cannot be accepted")
        for dimension in self.quality_regressions:
            reasons.append(
                f"{dimension.dimension} dropped by {dimension.drop} (limit {REGRESSION_LIMIT})"
            )
        if (
            self.quality_regressions
            and self.acceptance is not None
            and not self.acceptance.valid_on(self.today)
        ):
            reasons.append(
                f"the regression acceptance by {self.acceptance.owner} expired on "
                f"{self.acceptance.expires_on}"
            )
        return tuple(reasons)


def load_baselines(path: Path) -> tuple[Baseline, ...]:
    """Reads the append-only baseline register."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    return tuple(
        Baseline(
            identity=BaselineIdentity(
                agent=entry["agent"],
                agent_version=entry["agentVersion"],
                prompt_version=entry["promptVersion"],
                model_route=entry["modelRoute"],
                dataset_version=entry["datasetVersion"],
            ),
            approval=Approval.parse(entry["approval"]),
            hard_gate_passed=bool(entry["hardGatePassed"]),
            quality=dict(entry.get("quality", {})),
            superseded_by=entry.get("supersededBy"),
        )
        for entry in raw["baselines"]
    )


def approved_baseline_for(
    baselines: tuple[Baseline, ...], identity: BaselineIdentity
) -> Baseline | None:
    """The current approved baseline for this agent and dataset version, if one exists.

    Superseded entries are skipped but not deleted — the register is append-only, so the history of
    what was once approved stays readable.
    """
    for baseline in baselines:
        if baseline.superseded_by is None and baseline.identity.comparable_to(identity):
            return baseline
    return None


def compare(
    baseline: Baseline,
    identity: BaselineIdentity,
    hard_gate_passed: bool,
    quality: dict[str, str],
    acceptance: RegressionAcceptance | None = None,
    today: date | None = None,
) -> Comparison:
    """Compares a candidate result against an approved baseline."""
    if not baseline.identity.comparable_to(identity):
        # Refused rather than reported, because a comparison across dataset versions has no meaning
        # and returning "no regression" would be a confident wrong answer.
        raise BaselineError(
            f"cannot compare {identity.agent}/{identity.dataset_version} against "
            f"{baseline.identity.agent}/{baseline.identity.dataset_version}"
        )

    dimensions = tuple(
        DimensionComparison(
            dimension=name,
            baseline=baseline.score(name),
            candidate=None
            if quality.get(name, UNMEASURED) == UNMEASURED
            else Decimal(quality[name]),
        )
        for name in sorted(set(baseline.quality) | set(quality))
    )
    return Comparison(
        identity=identity,
        hard_gate_passed=hard_gate_passed,
        baseline_hard_gate_passed=baseline.hard_gate_passed,
        dimensions=dimensions,
        acceptance=acceptance,
        today=today or date.today(),
    )
