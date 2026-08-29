FROM node:24.15.0-alpine@sha256:d1b3b4da11eefd5941e7f0b9cf17783fc99d9c6fc34884a665f40a06dbdfc94f AS build
WORKDIR /app
COPY web-ui/package.json web-ui/package-lock.json ./
RUN npm ci
COPY web-ui/ ./

# Vite inlines `import.meta.env.VITE_*` at build time, so these are baked into the bundle and cannot
# be changed by an environment variable on the running container. That makes the identity of the
# OIDC issuer a property of the image, which is why it is a build argument rather than a default
# somebody edits in source.
#
# The default matches authClient.ts's own fallback, so an unparameterised build behaves exactly as
# before. Local Kubernetes overrides it with a URL that resolves identically from the browser and
# from inside the cluster -- see deploy/k8s/dev/README.md; a browser-only URL produces tokens whose
# `iss` the platform then rejects.
ARG VITE_KEYCLOAK_URL=http://localhost:8081
ARG VITE_KEYCLOAK_REALM=ramals
ARG VITE_KEYCLOAK_CLIENT_ID=ramals-web-ui
ARG VITE_API_BASE_URL=http://localhost:8080
# Written to .env.production rather than exported as ENV: Vite resolves import.meta.env from its
# .env files, and a process environment variable of the same name is not a dependable substitute --
# it builds without error and silently produces a bundle carrying the source defaults, which then
# fails at runtime in the browser rather than here.
RUN printf 'VITE_KEYCLOAK_URL=%s\nVITE_KEYCLOAK_REALM=%s\nVITE_KEYCLOAK_CLIENT_ID=%s\nVITE_API_BASE_URL=%s\n' \
      "$VITE_KEYCLOAK_URL" "$VITE_KEYCLOAK_REALM" "$VITE_KEYCLOAK_CLIENT_ID" "$VITE_API_BASE_URL" \
      > .env.production \
 && cat .env.production

RUN npm run build

# Fail the build if the bundle did not pick the values up. Without this the only symptom is a blank
# page in a browser, a long way from the cause.
RUN grep -rq "$VITE_KEYCLOAK_URL" dist/assets/*.js \
 || (echo "VITE_KEYCLOAK_URL '$VITE_KEYCLOAK_URL' is not present in the built bundle" && exit 1)

# Alpine 3.24.1 base, pinned by digest. The pin is what makes the build reproducible and it is also
# why the image cannot pick up a security fix on its own: the digest is frozen, so a CVE published
# after it was chosen stays in the image until somebody acts.
#
# CVE-2026-14456 (openssl, HIGH) is that case. Alpine v3.24/main carries the fix in 3.5.8-r0, but
# upstream nginx-unprivileged has not rebuilt against it, so there is no newer digest to move to --
# checked, rather than assumed: the current :alpine tag still ships 3.5.7-r0.
#
# Patching the packages here is therefore the fix, not a workaround for one. Re-pinning to a
# rebuilt upstream digest later is strictly better and this line should go when that exists.
FROM nginxinc/nginx-unprivileged:alpine@sha256:334d92979f15aaecd5dd50af5105e1230e2bb70765d45b1e2f964e7c5eda81c3
# The base image runs as uid 101. Root is needed to install and is given up again on the next line,
# so the running container is unprivileged exactly as before -- the whole point of this base.
USER root
RUN apk upgrade --no-cache libcrypto3 libssl3
USER 101
COPY infrastructure/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080

