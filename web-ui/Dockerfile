FROM node:24.15.0-alpine@sha256:d1b3b4da11eefd5941e7f0b9cf17783fc99d9c6fc34884a665f40a06dbdfc94f AS build
WORKDIR /app
COPY web-ui/package.json web-ui/package-lock.json ./
RUN npm ci
COPY web-ui/ ./
RUN npm run build

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

