# MVP-2 technical debt register

## Open items

| Item | Status | Finding | Required future remediation |
| --- | --- | --- | --- |
| **TD-M2-SEC-01c — Rate limiting is per-instance, not cluster-wide** | Open; deferred to horizontal scale-out | `TokenBucketRateLimiter` holds its buckets in the instance heap, so N replicas admit up to N times the configured allowance for one key. The registration abuse ceiling is unaffected — it counts in PostgreSQL and is proven across separate JVMs by `MultiReplicaRateLimitIntegrationTests` — but the HTTP tiers are enforced per pod. | Evaluate a shared limiter (Redis token bucket or an ingress-level limit) when the deployment first runs more than one replica. Until then the single-replica topology makes per-instance and cluster-wide enforcement identical, so this is latent rather than active. |

## Closed items

| Item | Closed | Finding | Remediation |
| --- | --- | --- | --- |
| **TD-M2-SEC-01a — `X-Forwarded-For` trusted without a proxy boundary** | Closed | The left-most `X-Forwarded-For` value was read whenever the header was present, so a directly reachable caller could rotate it and mint a fresh bucket per request — removing the ceiling rather than weakening it. | `ClientAddressResolver` consults the header only when the immediate peer matches a configured trusted-proxy CIDR, then walks the chain right-to-left to the first address no trusted proxy vouched for. Every failure path returns `getRemoteAddr()`. Hostname values are rejected so a header cannot induce a DNS lookup, and IPv4-mapped IPv6 is normalised so one host cannot occupy two buckets. Configured by `ramals.security.forwarding.*`. |
| **TD-M2-SEC-01b — Unbounded rate-limit key state** | Closed | The bucket map had no expiry, eviction or size cap: every key ever seen was retained for the process lifetime. With (a) fixed the key is an address the peer owns, but a routed IPv6 /64 supplies 2^64 of those, so rotation converted into steady heap growth and eventually an out-of-memory kill — a worse outcome than the requests being limited. | `TokenBucketRateLimiter` reclaims buckets that have refilled to capacity. This costs no enforcement: a full bucket and an absent bucket admit the next request identically, so the sweep removes only entries carrying no information. `RateLimitTier.getMaxKeys()` is the backstop beneath it (`ramals.security.rate-limit.max-keys`, `…​.subject.max-keys`); on reaching it, keys that already hold a bucket keep being served and unknown keys are shed, so a key-exhaustion flood costs new arrivals rather than every client at once. Sweeps are single-threaded and interval-throttled so the reclamation cannot itself become an amplifier. Covered by `TokenBucketRateLimiterTests` (reclamation, non-eviction of restricting buckets, shedding, and a 20,000-key rotation that must leave the table bounded). |

TD-M2-SEC-01 originally recorded review Finding 4 as a single row covering all three concerns. It is
split here because the halves diverged: (a) and (b) are remediated in code with tests, while (c) is a
property of the deployment topology and cannot be closed by this repository alone. Keeping them as
one row understated the fix and hid the part that is still real.
