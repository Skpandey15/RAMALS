# M0-T04 — Keycloak/OIDC and Zero Trust baseline

Security authority: `RAMALS_MVP0_Zero_Trust_Security_Architecture_v1.1.docx`.

## Identity contract

- The SPA is a public OIDC client using Authorization Code flow and S256 PKCE. Implicit and direct-password grants are disabled.
- The API accepts only signed JWT access tokens whose issuer is `RAMALS_OIDC_ISSUER_URI`, whose audience contains `ramals-api`, and whose time claims are valid. Signing keys are obtained from the issuer's JWKS metadata and rotate without application keys being stored in the repository.
- Approved realm roles are `LEARNER`, `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, and `SERVICE`. Unknown realm roles are not mapped into application authorities.
- The API is stateless and default-deny. Only health probes and CORS preflight are public.
- CORS uses the exact `RAMALS_WEB_ORIGIN`; credentials and wildcard origins are disabled for the bearer-token API.

## Authorization rules

- A valid token alone is insufficient. Controllers use explicit role or resource policy checks.
- Learner object access requires both `ROLE_LEARNER` and an exact match between the JWT `learner_id` claim and the path resource identifier. Instructor/cohort authorization remains denied until its assignment data source is implemented.
- `ADMIN` endpoints require `ROLE_ADMIN` plus an MFA authentication signal: `amr` containing `otp` or `mfa`, or numeric `acr` of at least 2.
- The database probe is operational-only and requires `SERVICE`, or `ADMIN` with MFA.

## ADMIN provisioning rule

Before granting the RAMALS `ADMIN` realm role, an identity administrator must assign Keycloak's `CONFIGURE_TOTP` required action and verify successful OTP enrollment. Do not use the bootstrap Keycloak administrator for application work. The API independently denies every ADMIN operation when the issued token lacks the MFA signal, so a mistaken role assignment does not bypass MFA.

## Browser token handling

`web-ui/src/auth/authClient.ts` owns the single `keycloak-js` instance. Access and refresh tokens remain in that in-memory adapter instance and are never written to browser persistence, URLs, or logs. The authorized fetch wrapper refreshes short-lived tokens immediately before use and adds them only to the request `Authorization` header.

## Evidence

Backend tests cover unauthenticated `401`, unauthorized `403`, wrong audience, expired token, cross-learner IDOR denial, ADMIN without MFA denial, and approved Keycloak role mapping. Frontend tests statically enforce the absence of browser persistence APIs and the presence of standard flow plus S256 PKCE.

Run without starting or changing local containers:

```text
./gradlew :learning-platform:test
cd web-ui
npm test
npm run lint
npm run build
```
