# TD-T18-02 — closure evidence

TD-T18-02 recorded that every link of the adaptation chain was proven **except the gate**. The plane
returned `UNPROCESSABLE_PROPOSAL` and `AdaptationService` caught it first, because the shipped
deterministic route's `FakeProvider` returns a plain string that every agent validator rejects by
design. `agent_run_id`, `prompt_template_id`, `prompt_version` and `model_route` were therefore NULL
on every row.

Closing it required a run with a real `RAMALS_AI_MODEL_ROUTE` and a provider credential. This is that
run.

**Outcome: PASS**

## Qualification configuration

| | |
| --- | --- |
| Candidate | `v0.1.0-rc8` @ `0c01c8518abae97bf986700a9af616e17a656aea` |
| `model_route` | `adaptation-default` |
| Provider | **OpenAI** |
| Concrete model | `gpt-4.1-2025-04-14` |
| Resolved route table | `ROUTE_TABLE_V1+adaptation-default:model=gpt-4.1-2025-04-14` |
| Prompt | `ADAPTATION_PLAN` / `ADAPTATION_PROMPT_V1` |
| Credential source | `RAMALS_AI_PROVIDER_API_KEY`, read from the **User-scope environment only** |
| Credential persistence | **none** — see *Credential handling* below |

The candidate was not rebuilt, retagged or modified. Only the AI plane's runtime configuration
changed, and only `ramals-ai` was recreated; the backend was left running so its `RAMALS_AI_BASE_URL`
wiring was untouched.

Provider dispatch confirmed by the plane's own log rather than inferred from configuration:

```
LiteLLM completion() model= gpt-4.1-2025-04-14; provider = openai
```

The plane accepting a live route at startup also independently confirms the RC8 image ships the
provider SDK — the defect RC8 exists to correct.

## Mandatory criteria

| Criterion | Result |
| --- | --- |
| A real model route, not `ci-fake` | ✅ `adaptation-default` |
| A provider credential supplied | ✅ from User-scope environment |
| A live model went **through** the gate | ✅ `status = SUCCEEDED`, `error_code = NULL` on 5/5 |
| `AdaptationProposalGate` and its validators unweakened and unbypassed | ✅ no code change; RC8 byte-identical |
| `agent_run_id` populated | ✅ 5/5 |
| `prompt_template_id` populated | ✅ `ADAPTATION_PLAN` |
| `prompt_version` populated | ✅ `ADAPTATION_PROMPT_V1` |
| `model_route` populated | ✅ `adaptation-default` |
| Deterministic recommendation unaffected | ✅ 5 recommendations produced and served |
| `ci-fake` not substituted | ✅ live provider dispatch logged |

Before and after, on the same table:

| | Before | After |
| --- | --- | --- |
| `SUCCEEDED` rows | 0 | **5** |
| rows with `agent_run_id` | 0 | **5** |
| `error_code` | `UNPROCESSABLE_PROPOSAL` on all | **NULL** |

## The chain, end to end

Driven by `scripts/validation/keycloak-e2e.py` against the deployed stack under a **real
Keycloak-issued learner token**. All 22 of its checks passed, including `submission completes`, which
is the event that fires the comparison, and `a recommendation was produced`.

One learner submission produced five deterministic decisions — one per skill — and five dispatches:

| Stage | Observed |
| --- | --- |
| `interactionId` | `01a02918-c19f-772e-95e8-fa78432e663b` (single, shared by all five) |
| `traceId` | `de8d48c00ba999b9c5ab7e594b708e8a` |
| deterministic decisions | 5 recommendations, 5 distinct `decision_record_id`, committed `10:51:23.549` |
| dispatches | 5 `ai_execution` rows, started `10:51:30`–`10:51:31` |
| durable events | `ai_execution_event`: 5 × `STARTED`, 5 × `SUCCEEDED`, 1 interaction |

The decisions commit *before* the dispatches, which is the `AFTER_COMMIT` contract holding in a
deployed environment rather than in a test.

Each execution carries its own deterministic `request_id`, derived from
`AGENT_TYPE|interactionId|skillId` — five distinct ids under one interaction, which is what makes a
replay collide by construction rather than by the caller behaving.

| `request_id` | `agent_run_id` | status |
| --- | --- | --- |
| `b62fa845-3715-3694…` | `01a02918-c37d-727b…` | SUCCEEDED |
| `ad5a1d7d-43a2-3123…` | `01a02918-d069-74f5…` | SUCCEEDED |
| `d022301a-1c3b-312e…` | `01a02918-d5e3-768c…` | SUCCEEDED |
| `502321da-871b-3c6b…` | `01a02918-dba1-75db…` | SUCCEEDED |
| `4f56eb34-570f-3732…` | `01a02918-e038-70de…` | SUCCEEDED |

## Cost and latency

| Metric | Value |
| --- | --- |
| Executions | 5 |
| Input / output tokens | 242 in, 64–73 out |
| Cost per execution | $0.000996 – $0.001068 |
| **Total spend** | **$0.005124** |
| Latency | 1062–3262 ms |

Every execution is inside the route's 6000 ms p95 budget and far below its $0.040 hard ceiling. The
per-call cost is consistent with the OpenAI binding's prices, not the Anthropic ones.

## Credential handling

* Supplied only as `RAMALS_AI_PROVIDER_API_KEY`, read at runtime from the **User-scope environment**.
* Never written to `deploy/.env`, to any committed file, to this document, or to any log.
* Never echoed. Presence was verified by length and prefix classification only.
* Cleared from the invoking process immediately after the container was recreated.
* `core.ai_execution` has no column that could carry it: the table stores digests, never payloads.
* The AI plane was returned to `ci-fake` after this evidence was captured, so no billable route is
  left enabled.

## Known gap recorded, not closed here

`ai_execution.model_id` is **NULL on all five qualified executions**. `model_route` identifies only
the logical route, and with more than one vendor approved behind a route the persisted record cannot
independently establish which concrete provider or model produced a response.

The information existed at the time of the call — the resolved route table version names the model —
but is not persisted on the execution row. Tracked as **TD-RC8-02**. Deliberately not implemented
here: no MVP-1 release criterion requires concrete-model provenance, and TD-T18-02's own closure
condition names `model_route`, not `model_id`.
