# MVP-2 technical debt register

## Open items

| Item | Status | Finding | Required future remediation |
| --- | --- | --- | --- |
| **TD-M2-SEC-01 — Rate-limit trust boundary and bounded state** | Open; explicitly deferred outside M2-T09 | `X-Forwarded-For` is trusted without an established trusted-proxy boundary, so directly reachable callers can spoof or rotate it. The in-memory `ConcurrentHashMap` has no expiry or eviction, permitting unbounded unique-key growth, and enforcement is per application instance rather than cluster-wide. | Establish trusted-proxy configuration and use `remoteAddr` for untrusted upstreams; replace unbounded state with a bounded/expiring cache such as Caffeine or an equivalent; evaluate a distributed limiter if cluster-wide enforcement is required. |

This item records review Finding 4. M2-T09 does not modify `RateLimitFilter` or
`TokenBucketRateLimiter`, and this debt is not represented as fixed by the diagnostic-gate
remediation.
