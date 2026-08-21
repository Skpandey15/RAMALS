#!/usr/bin/env bash
# Derives the Keycloak base URL from the OIDC token endpoint.
#
# Two things need to reach Keycloak during a run, and they were told about it in different ways.
# k6 is given RAMALS_TOKEN_URL -- the full token endpoint -- because that is what it POSTs to.
# provision-load-fixtures.py needs the server root instead, because it drives the admin API, and it
# reads that from RAMALS_KEYCLOAK_URL.
#
# Nothing set the second one. Its default is 'http://keycloak:8080', a Compose service name that
# resolves on the SUT and nowhere else, so on a two-host run the fixtures failed to resolve a
# hostname while k6 had a perfectly good address for the same server sitting in the environment.
# The operator had supplied the information; it just was not passed along.
#
# Deriving it removes the second variable rather than documenting it. One address goes in, both
# consumers get what they need, and there is no way to set them to two different servers.
#
# Sourced rather than inlined so the transform can be tested directly -- see
# scripts/ci/test-perf-attestation.sh. A derivation nobody can test is how the first gap got here.

# keycloak_base_url_from_token_url <token-url>
#
# Prints the server root and returns 0; returns 1 and prints nothing if the argument is not a
# realm-scoped token endpoint. Failure is a real answer: an argument this cannot parse is one where
# guessing a prefix would produce a plausible URL pointing at nothing.
keycloak_base_url_from_token_url() {
  case "${1:-}" in
    */realms/*)
      # Strips from the FIRST /realms/ occurrence, so a realm literally named 'realms' cannot
      # truncate the result in the wrong place.
      printf '%s' "${1%%/realms/*}"
      ;;
    *)
      return 1
      ;;
  esac
}

# export_keycloak_base_url
#
# Sets RAMALS_KEYCLOAK_URL from RAMALS_TOKEN_URL when the caller has not set it explicitly. An
# explicit value always wins: a deployment whose admin API is somewhere other than the token
# issuer is unusual but legitimate, and this must not overrule the operator.
export_keycloak_base_url() {
  [ -n "${RAMALS_KEYCLOAK_URL:-}" ] && return 0
  [ -n "${RAMALS_TOKEN_URL:-}" ] || return 0

  local derived
  derived="$(keycloak_base_url_from_token_url "${RAMALS_TOKEN_URL}")" || return 0
  RAMALS_KEYCLOAK_URL="${derived}"
  export RAMALS_KEYCLOAK_URL
}
