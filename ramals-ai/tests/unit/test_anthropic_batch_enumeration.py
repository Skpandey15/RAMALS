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
        truncated: set[str] | None = None,
    ) -> None:
        self._batches = list(batches)
        self._contents = contents
        self._unreadable = unreadable or set()
        self._truncated = truncated or set()
        self.list_calls = 0
        self.result_calls: list[str] = []
        self.create_calls = 0
        self.list_failure: Exception | None = None

    def list(self, *, limit: int = 100) -> Sequence[_Batch]:  # noqa: ARG002 - SDK shape
        """Auto-paginating, exactly as the SDK's SyncPage is.

        The earlier fake took a ``before_id`` and returned one slice, modelling a cursor the SDK
        does not require and a direction the API does not have. That fiction is what let a real
        pagination defect through: ``before_id`` walks toward *newer* items, so the production loop
        re-inspected batches and reported a false duplicate. A fake must not invent an interface
        the real client does not present.
        """
        self.list_calls += 1
        if self.list_failure is not None:
            raise self.list_failure
        return list(self._batches)

    def results(self, batch_id: str) -> Sequence[_Record]:
        self.result_calls.append(batch_id)
        if batch_id in self._unreadable:
            raise RuntimeError("results stream failed")
        if batch_id in self._truncated:
            return _TruncatedStream(self._contents.get(batch_id, []))
        return [_Record(custom_id) for custom_id in self._contents.get(batch_id, [])]

    def create(self, **_kwargs: Any) -> Any:  # pragma: no cover - must never be reached
        self.create_calls += 1
        raise AssertionError("enumeration must never create a provider execution")


class _TruncatedStream(Sequence[_Record]):
    """Yields part of a batch's records and then fails.

    The nastiest shape in this file, and the reason it exists. A stream that fails *before* yielding
    anything is obviously unknown; one that fails half way looks like a complete read right up to
    the moment it does not. If the search treated that as a finished inspection it would memoise a
    batch whose remaining records were never seen -- and the target could have been in them.
    """

    def __init__(self, custom_ids: Sequence[str]) -> None:
        self._custom_ids = list(custom_ids)

    def __iter__(self) -> Any:
        for custom_id in self._custom_ids[:1]:
            yield _Record(custom_id)
        raise RuntimeError("results stream failed part-way")

    def __len__(self) -> int:  # pragma: no cover - iteration is the access path
        return len(self._custom_ids)

    def __getitem__(self, index: Any) -> Any:  # pragma: no cover - iteration is the access path
        return _Record(self._custom_ids[index])


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


def test_no_batch_is_ever_inspected_twice() -> None:
    fake, _ = _corpus(249)

    _search(fake, max_pages=10, max_inspections=300)

    # The regression that matters. Inspecting a batch twice puts it in the match list twice, and
    # two entries for one real execution reports MULTIPLE -- a duplicate that does not exist, which
    # refuses adoption and strands a recoverable execution.
    assert len(fake.result_calls) == len(set(fake.result_calls)), "no batch inspected twice"
    assert fake.list_calls == 1, "the SDK page auto-paginates; a manual cursor re-walks it"


def test_one_real_execution_is_never_reported_as_a_duplicate() -> None:
    # The W2 finding, as a unit test: a single batch carrying the key must be ONE.
    fake, batches = _corpus(7, size=45)

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.ONE
    assert len(result.matches) == 1
    assert result.matches[0].provider_execution_id == batches[7].id


def test_a_repeated_batch_in_the_listing_cannot_manufacture_a_duplicate() -> None:
    # Defensive: even if the provider yielded one batch twice, it is still one execution.
    target = _Batch("msgbatch_repeated", IN_WINDOW)
    fake = _FakeBatches([target, target], {target.id: [TARGET]})

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.ONE
    assert len(result.matches) == 1


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


def test_one_match_with_an_uninspectable_candidate_is_inconclusive_not_one() -> None:
    # The W2 P4 finding. A candidate that has not ended may be a SECOND execution carrying the same
    # key, so a single match found alongside one is not yet "exactly one" -- adopting on that
    # evidence silently picks one of a duplicate pair.
    found = _Batch("msgbatch_found", IN_WINDOW)
    still_running = _Batch("msgbatch_running", IN_WINDOW, ended=False)
    fake = _FakeBatches([found, still_running], {found.id: [TARGET]})

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE
    assert result.batches_uninspectable == 1
    # The match is still reported, so a caller can see what was found -- it just may not act on it.
    assert len(result.matches) == 1


def test_one_match_with_a_bound_hit_is_inconclusive_not_one() -> None:
    fake, batches = _corpus(0, size=60)

    result = _search(fake, max_inspections=5)

    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE
    assert result.limit_reached == "inspections"


def test_multiple_wins_even_when_the_search_was_incomplete() -> None:
    # Two is already more than one, whatever else was missed. Definitive.
    first = _Batch("msgbatch_dup_a", IN_WINDOW)
    second = _Batch("msgbatch_dup_b", IN_WINDOW)
    running = _Batch("msgbatch_running", IN_WINDOW, ended=False)
    fake = _FakeBatches([first, second, running], {first.id: [TARGET], second.id: [TARGET]})

    result = _search(fake)

    assert result.outcome is DurableSearchOutcome.MULTIPLE


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
    # max_pages bounds items listed (pages of 100): the SDK page auto-paginates, so there is no
    # per-page call to count.
    assert result.batches_listed == 200


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


# -- the durable negative memo, M2-ADR-020 section 3.1 ---------------------------------------------


def test_an_excluded_batch_is_never_opened() -> None:
    fake, batches = _corpus(None, size=5)
    already_ruled_out = [batches[0].id, batches[1].id]

    _search(fake, exclude_ids=already_ruled_out)

    # Asserting on the call log, not the outcome. The outcome would be ZERO whether or not the
    # exclusions saved anything, and the entire point of the memo is the calls it does not make.
    assert already_ruled_out[0] not in fake.result_calls
    assert already_ruled_out[1] not in fake.result_calls
    assert len(fake.result_calls) == 3


def test_an_excluded_batch_counts_as_covered_not_as_uninspectable() -> None:
    fake, batches = _corpus(None, size=5)

    result = _search(fake, exclude_ids=[b.id for b in batches[:4]])

    # The distinction decides the outcome. Counted as uninspectable, four exclusions would make
    # every future search INCONCLUSIVE forever and ZERO would become unreachable.
    assert result.batches_excluded == 4
    assert result.batches_uninspectable == 0
    assert result.outcome is DurableSearchOutcome.ZERO


def test_only_ended_fully_read_non_matching_batches_are_offered_for_memoisation() -> None:
    batches = [
        _Batch("msgbatch_ended_nomatch", IN_WINDOW),
        _Batch("msgbatch_running", IN_WINDOW, ended=False),
        _Batch("msgbatch_unreadable", IN_WINDOW),
        _Batch("msgbatch_carries", IN_WINDOW),
    ]
    fake = _FakeBatches(
        batches,
        {
            "msgbatch_ended_nomatch": ["custom-someone-else"],
            "msgbatch_unreadable": ["custom-someone-else"],
            "msgbatch_carries": [TARGET],
        },
        unreadable={"msgbatch_unreadable"},
    )

    result = _search(fake)

    # Exactly one of the four qualifies. The running one has no results, the unreadable one told us
    # nothing, and the matching one is evidence rather than a negative.
    assert result.newly_excluded_ids == ("msgbatch_ended_nomatch",)


def test_a_truncated_result_stream_is_never_memoised() -> None:
    batches = [_Batch("msgbatch_truncated", IN_WINDOW), _Batch("msgbatch_clean", IN_WINDOW)]
    fake = _FakeBatches(
        batches,
        {
            # The target sits *after* the record the stream manages to yield, so a search that
            # treated a truncated read as complete would both miss it and record that it had
            # looked.
            "msgbatch_truncated": ["custom-someone-else", TARGET],
            "msgbatch_clean": ["custom-someone-else"],
        },
        truncated={"msgbatch_truncated"},
    )

    result = _search(fake)

    assert "msgbatch_truncated" not in result.newly_excluded_ids
    assert result.batches_uninspectable == 1
    # And it must not read as absence either: the target really was in there.
    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE


def test_a_batch_carrying_the_key_is_never_memoised_as_a_negative() -> None:
    fake, batches = _corpus(2, size=5)

    result = _search(fake)

    assert batches[2].id not in result.newly_excluded_ids
    assert result.outcome is DurableSearchOutcome.ONE


def test_exclusions_do_not_hide_a_match_found_this_time() -> None:
    fake, batches = _corpus(4, size=5)

    result = _search(fake, exclude_ids=[batches[0].id, batches[1].id])

    assert result.outcome is DurableSearchOutcome.ONE
    assert result.matches[0].provider_execution_id == batches[4].id


# -- the per-pass inspection budget, M2-ADR-020 section 3.2 ----------------------------------------


def test_an_exhausted_budget_is_inconclusive_never_zero() -> None:
    fake, _ = _corpus(None, size=10)

    result = _search(fake, max_inspections=3)

    assert result.batches_inspected == 3
    assert result.limit_reached == "inspections"
    # It found nothing, and it must not say so: seven candidates were never opened.
    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE


def test_an_exhausted_budget_is_inconclusive_never_one() -> None:
    fake, batches = _corpus(0, size=10)

    result = _search(fake, max_inspections=1)

    # The target was found on the first inspection, and that is still not enough. An uninspected
    # candidate may be a *second* execution carrying the same key, which is the duplicate the
    # Definition of Done exists to surface.
    assert len(result.matches) == 1
    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE


def test_multiple_still_wins_when_the_budget_runs_out() -> None:
    batches = [_Batch(f"msgbatch_{i:04d}", IN_WINDOW) for i in range(6)]
    contents: dict[str, Sequence[str]] = {b.id: ["custom-someone-else"] for b in batches}
    contents[batches[0].id] = [TARGET]
    contents[batches[1].id] = [TARGET]
    fake = _FakeBatches(batches, contents)

    result = _search(fake, max_inspections=2)

    # Two is already more than one, and no further looking can reduce it. Degrading this to
    # INCONCLUSIVE would let the lifecycle keep searching for a duplicate it has already proven.
    assert result.outcome is DurableSearchOutcome.MULTIPLE
    assert len(result.matches) == 2


def test_a_budget_resumes_across_attempts_when_negatives_are_remembered() -> None:
    """The interaction the whole design rests on: bounded searches must converge.

    A budget without the memo does not slow a search down, it stops it finishing -- the same first
    candidates are re-read every attempt and the window is never covered. Three passes of three
    inspections over an eight-batch window must reach a conclusion; without carrying the negatives
    forward they never would.
    """
    fake, batches = _corpus(7, size=8)
    ruled_out: list[str] = []
    outcomes = []

    for _ in range(3):
        result = _search(fake, max_inspections=3, exclude_ids=list(ruled_out))
        ruled_out.extend(result.newly_excluded_ids)
        outcomes.append(result.outcome)

    assert outcomes[0] is DurableSearchOutcome.INCONCLUSIVE
    assert outcomes[-1] is DurableSearchOutcome.ONE
    # Each batch opened exactly once across all three passes -- the point of the memo.
    assert sorted(fake.result_calls) == sorted(b.id for b in batches)


def test_without_the_memo_a_bounded_search_never_converges() -> None:
    """The negative control for the test above.

    Same window, same budget, exclusions discarded between attempts. The search re-reads the same
    three candidates forever and the eighth batch is never reached -- a livelock that ends as
    horizon exhaustion twenty-six hours later. This is what proves the memo and the budget are one
    mechanism rather than two independent improvements.
    """
    fake, batches = _corpus(7, size=8)

    outcomes = [_search(fake, max_inspections=3).outcome for _ in range(3)]

    assert outcomes == [DurableSearchOutcome.INCONCLUSIVE] * 3
    assert batches[7].id not in fake.result_calls
    assert set(fake.result_calls) == {b.id for b in batches[:3]}


def test_cumulative_coverage_is_required_before_zero() -> None:
    fake, batches = _corpus(None, size=6)

    partial = _search(fake, max_inspections=4)
    assert partial.outcome is DurableSearchOutcome.INCONCLUSIVE

    complete = _search(fake, max_inspections=4, exclude_ids=list(partial.newly_excluded_ids))

    # ZERO only once every candidate has been covered -- four in the first pass, two in the second.
    assert complete.outcome is DurableSearchOutcome.ZERO
    assert complete.batches_excluded == 4
    assert complete.batches_inspected == 2


def test_a_cached_negative_plus_an_unfinished_candidate_is_still_inconclusive() -> None:
    batches = [
        _Batch("msgbatch_known_negative", IN_WINDOW),
        _Batch("msgbatch_still_running", IN_WINDOW, ended=False),
    ]
    fake = _FakeBatches(batches, {"msgbatch_known_negative": ["custom-someone-else"]})

    result = _search(fake, exclude_ids=["msgbatch_known_negative"])

    # Full coverage of everything readable is still not full coverage. The unfinished batch is the
    # one most likely to be the orphan, which is exactly why this may not read as ZERO.
    assert result.batches_excluded == 1
    assert result.batches_uninspectable == 1
    assert result.outcome is DurableSearchOutcome.INCONCLUSIVE


def test_excluding_every_candidate_still_never_creates_anything() -> None:
    fake, batches = _corpus(None, size=5)

    _search(fake, exclude_ids=[b.id for b in batches])

    assert fake.create_calls == 0
    assert fake.result_calls == []


# -- Retry-After, M2-ADR-020 section 7 ------------------------------------------------------------


class _RateLimitedError(Exception):
    """Shaped like the SDK's RateLimitError: classified by class name, carrying a response."""

    def __init__(self, retry_after: object) -> None:
        super().__init__("429")
        self.status_code = 429
        self.response = type("_Response", (), {"headers": {"retry-after": retry_after}})()


def _rate_limited(retry_after: object) -> Any:
    fake, _ = _corpus(None, size=3)
    fake.list_failure = type("RateLimitError", (_RateLimitedError,), {})(retry_after)
    from ramals_ai.gateway.errors import GatewayError

    with pytest.raises(GatewayError) as raised:
        _search(fake)
    return raised.value


def test_a_rate_limit_is_classified_and_carries_retry_after() -> None:
    failure = _rate_limited("30")

    from ramals_ai.gateway.errors import GatewayErrorCode

    # Classified as a rate limit rather than a generic outage, because the two call for opposite
    # responses: one may be retried on the same cadence and the other must not be.
    assert failure.code is GatewayErrorCode.PROVIDER_RATE_LIMITED
    assert failure.retry_after_ms == 30_000


def test_an_unparseable_retry_after_falls_back_rather_than_failing() -> None:
    # The HTTP-date form is legal and deliberately not parsed. Falling back to the caller's own
    # backoff is correct; raising while handling another failure is not.
    assert _rate_limited("Wed, 21 Oct 2026 07:28:00 GMT").retry_after_ms is None
    assert _rate_limited(None).retry_after_ms is None
    assert _rate_limited("-5").retry_after_ms is None


def test_an_outage_carries_no_retry_after() -> None:
    fake, _ = _corpus(None, size=3)
    fake.list_failure = type("APIConnectionError", (Exception,), {})("down")
    from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode

    with pytest.raises(GatewayError) as raised:
        _search(fake)

    assert raised.value.code is GatewayErrorCode.PROVIDER_UNAVAILABLE
    assert raised.value.retry_after_ms is None
