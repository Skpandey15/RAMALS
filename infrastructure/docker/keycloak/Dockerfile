FROM quay.io/keycloak/keycloak:26.7.1@sha256:f1f1f01e472c8a78df40d8f2a49a925274eda4d3d80d5f6edbb5c880ee3c01c6 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.1@sha256:f1f1f01e472c8a78df40d8f2a49a925274eda4d3d80d5f6edbb5c880ee3c01c6
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# The login theme is plain FreeMarker and CSS inherited from keycloak.v2, so it resolves at runtime
# and needs no `kc.sh build` re-augmentation -- which is why this COPY sits after the builder stage
# rather than inside it. The theme ships with the image but stays dormant until a realm selects it,
# so an environment that never sets loginTheme is byte-identical in behaviour to before.
COPY infrastructure/docker/keycloak/themes/ramals /opt/keycloak/themes/ramals
COPY infrastructure/docker/keycloak/ramals-realm.json /opt/keycloak/data/import/ramals-realm.json
COPY infrastructure/docker/keycloak/reconcile-identity-admin.sh /opt/keycloak/bin/reconcile-identity-admin.sh
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
