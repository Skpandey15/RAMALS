"""Lost-acknowledgement recovery by `custom_id` enumeration (M2-ADR-020).

The single fact that shapes every test here: **batch list metadata carries no `custom_id`**. The
listing can only narrow the field, and the correlation has to come from opening each candidate's
results. So the interesting cases are not "did it find the batch" but "what did it do when it could
not look" — because a batch that has not ended has no results, and calling that a non-match reports
"no orphan exists" at the moment one is most likely running.

The fake pages exactly as the SDK does, newest first via `before_id`, so the pagination assertions
are about the real traversal rather than a convenient one.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import pytest

from ramals_ai.gateway.providers.anthropic_batches_adapter import AnthropicBatchesProvider
from ramals_ai.gateway.providers.base import DurableSearchOutcome

WINDOW_START = "2026-08-28T10:00:00+00:00"
WINDOW_END = "2026-08-28T12:00:00+00:00"
IN_WINDOW = "2026-08-28T11:00:00+00:00"
TARGET = "custom-req-lostack-0001"


class _Batch:
    def __init__(self, identifier: str, created: str, *, ended: bool = True) -> None:
        self.id = identifier
        self.created_at = created
        self.ended_at = created if ended else None
        self.processing_status = "ended" if ended else "in_progress"
        # The field that decides inspectability. Null while a batch is still processing, which is
        # exactly when a lost-acknowledgement orphan is most likely to be found.
        self.results_url = f"https://example/{identifier}" if ended else None
        self.request_counts = None


class _Record:
    def __init__(self, custom_id: str) -> None:
        self.custom_id = custom_id
        self.result = _Succeeded()


class _Succeeded:
    type = "succeeded"

    class _Message:
        content: list[Any] = []
        id = "msg_fake"

        class Usage:
            input_tokens = 16
            output_tokens = 4
            cache_read_input_tokens = 0

        usage = Usage()

    message = _Message()


class _FakeBatches:
    """Pages newest-first through a fixed corpus, exactly as the SDK's cursor does."""

    def __init__(
        self,
        batches: Sequence[_Batch],
        contents: dict[str, Sequence[str]],
        unreadable: set[str] | None = None,
    ) -> None:
        self._batches = list(batches)
        self._contents = contents
        self._unreadable = unreadable or set()
        self.list_calls = 0
        self.result_calls: list[str] = []
        self.create_calls = 0
        self.list_failure: Exception | None = None

    def list(self, *, limit: int = 100, before_id: str | None = None) -> Sequence[_Batch]:
        self.list_calls += 1
        if self.list_failure is not None:
            raise self.list_failure
        start = 0
        if before_id is not None:
            start = next(i for i, b in enumerate(self._batches) if b.id == before_id) + 1
        return self._batches[start : start + limit]

    def results(self, batch_id: str) -> Sequence[_Record]:
        self.result_calls.append(batch_id)
        if batch_id in self._unreadable:
            raise RuntimeError("results stream failed")
        return [_Record(custom_id) for custom_id in self._contents.get(batch_id, [])]

    def create(self, **_kwargs: Any) -> Any:  # pragma: no cover - must never be reached
        self.create_calls += 1
        raise AssertionError("enumeration must never create a provider execution")


def _provider(fake: _FakeBatches) -> AnthropicBatchesProvider:
    provider = AnthropicBatchesProvider(api_key="unused-in-tests")
    provider._batches = lambda: fake  # type: ignore[method-assign]
    return provider


def _corpus(target_index: int | None, size: int = 250) -> tuple[_FakeBatches, list[_Batch]]:
    """`size` in-window batches, newest first, with the target at `target_index` if given."""
    batches = [_Batch(f"msgbatch_{i:04d}", IN_WINDOW) for i in range(size)]
    contents: dict[str, Sequence[str]] = {b.id: ["custom-someone-else"] for b in batches}
    if target_index is not None:
        contents[batches[target_index].id] = ["custom-other", TARGET]
    return _FakeBatches(batches, contents), batches


def _search(fake: _FakeBatches, **kwargs: Any) -> Any:
    return _provider(fake).find_executions_by_custom_id(TARGET, WINDOW_START, WINDOW_END, **kwargs)


# -- pagination ------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "position,index",
    [("first page", 3), ("middle page", 140), ("last page", 249)],
)
def test_the_target_is_found_wherever_it_sits_in_the_pages(position: str, index: int) -> None:
    fake, batches = _corpus(index)

    result = _search(fake, max_pages=10, max_inspections=300)

    assert result.outcome is DurableSearchOutcome.ONE, position
    assert result.matches[0].provider_execution_id == batches[index].id
    assert result.pages_fetched >= 1
    if index >= 100:
        # It genuinely paged rather than finding everything in the first response.
        assert result.pages_fetched > 1


def test_pagination_uses_the_cursor_rather_than_refetching_the_first_page() -> None:
    fake, _ = _corpus(249)

    _search(fake, max_pages=10, max_inspections=300)

    # 250 batches at 100 per page: three pages, then one empty page that ends the walk.
    assert fake.list_calls in (3, 4)
    assert len(fake.result_calls) == len(set(fake.result_calls)), "no batch inspected twice"


def test_batches_outside_the_window_are_never_opened() -> None:
    inside = _Batch("msgbatch_inside", IN_WINDOW)
    too_new = _Batch("msgbatch_toonew", "2026-08-28T18:00:00+00:00")
    too_old = _Batch("msgbatch_tooold", "2026-08-27T01:00:00+00:00")
    fake = _FakeBatches([too_new, inside, too_old], {inside.id: [TARGET]})

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.ONE
    # The expensive call is made only for candidates the window admits.
    assert fake.result_calls == [inside.id]


# -- the four outcomes -----------------------------------------------------------------------------


def test_zero_matches_when_every_candidate_was_inspected() -> None:
    fake, _ = _corpus(None, size=5)

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.ZERO
    assert result.matches == ()
    assert result.batches_uninspectable == 0
    assert result.limit_reached is None


def test_exactly_one_match_reports_its_identity_and_usage() -> None:
    fake, batches = _corpus(2, size=5)

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.ONE
    match = result.matches[0]
    assert match.provider_execution_id == batches[2].id
    assert match.custom_id == TARGET
    assert match.outcome == "succeeded"
    # Usage travels with the match, because a duplicate whose tokens are unrecorded is invisible
    # in the bill however visible it is in a log.
    assert match.input_tokens == 16
    assert match.output_tokens == 4


def test_multiple_matches_are_all_returned_and_none_is_chosen() -> None:
    first = _Batch("msgbatch_dup_one", IN_WINDOW)
    second = _Batch("msgbatch_dup_two", IN_WINDOW)
    fake = _FakeBatches([first, second], {first.id: [TARGET], second.id: [TARGET]})

    result = _search(fake)

    # The duplicate criterion 3 exists to surface. The adapter reports both and picks neither;
    # there is no rule that would make choosing correct.
    assert result.outcome is DurableSearchOutcome.MULTIPLE
    assert {m.provider_execution_id for m in result.matches} == {first.id, second.id}


def test_an_unfinished_batch_makes_the_search_inconclusive_not_zero() -> None:
    # The load-bearing case. A batch still processing has no results to read, so it cannot be
    # correlated -- and reporting ZERO here would assert no orphan exists at the exact moment one
    # is most likely to be running.
    running = _Batch("msgbatch_running", IN_WINDOW, ended=False)
    other = _Batch("msgbatch_other", IN_WINDOW)
    fake = _FakeBatches([running, other], {other.id: ["custom-someone-else"]})

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE
    assert result.batches_uninspectable == 1
    assert running.id not in fake.result_calls, "an unfinished batch has nothing to open"


def test_unreadable_results_make_the_search_inconclusive_not_zero() -> None:
    broken = _Batch("msgbatch_broken", IN_WINDOW)
    fake = _FakeBatches([broken], {}, unreadable={broken.id})

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE
    assert result.batches_uninspectable == 1


# -- bounds ----------------------------------------------------------------------------------------


def test_hitting_the_inspection_bound_is_inconclusive_never_zero() -> None:
    fake, _ = _corpus(None, size=60)

    result = _search(fake, max_inspections=10)

    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE
    assert result.limit_reached == "inspections"
    assert len(fake.result_calls) == 10, "the bound must actually bound the expensive call"


def test_hitting_the_page_bound_is_inconclusive_never_zero() -> None:
    fake, _ = _corpus(None, size=500)

    result = _search(fake, max_pages=2, max_inspections=1000)

    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE
    assert result.limit_reached == "pages"
    assert fake.list_calls == 2


# -- failure and safety ----------------------------------------------------------------------------


def test_a_provider_outage_during_enumeration_raises_rather_than_reporting_zero() -> None:
    fake, _ = _corpus(None, size=5)
    fake.list_failure = RuntimeError("APIConnectionError")

    from ramals_ai.gateway.errors import GatewayError

    # Raising is the fail-closed answer. Returning ZERO would let a caller conclude no orphan
    # exists because the provider was unreachable.
    with pytest.raises(GatewayError):
        _search(fake)


def test_enumeration_never_creates_a_provider_execution() -> None:
    for index in (None, 0, 4):
        fake, _ = _corpus(index, size=5)
        _search(fake)
        assert fake.create_calls == 0
