# API contracts

OpenAPI contracts and API standards will be maintained here. Cross-language contracts are defined once and generated or validated on both sides.

## Curriculum graph

- `GET /api/v1/curricula/{domainCode}/versions/{versionCode}/skills`
- `GET /api/v1/curricula/{domainCode}/versions/{versionCode}/skills/{skillCode}/prerequisites`

Both endpoints require an authenticated learner, instructor, or content-author
role and return only published or retired versioned curriculum data. Unknown
versions and skill codes return the safe `CURRICULUM_NOT_FOUND` problem code.
