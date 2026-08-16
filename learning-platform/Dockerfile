FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle gradle.properties ./
COPY learning-platform/build.gradle learning-platform/build.gradle
RUN chmod +x gradlew && ./gradlew --no-daemon :learning-platform:dependencies

COPY learning-platform/src learning-platform/src
RUN ./gradlew --no-daemon :learning-platform:bootJar

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
# This is the newest published temurin JRE Alpine image, but it still ships a p11-kit with a
# fixed-upstream HIGH advisory. Apply available Alpine security patches at build time so the
# runtime image carries no fixable CRITICAL/HIGH findings; verified to scan clean.
RUN apk --no-cache upgrade \
 && addgroup -S -g 10001 ramals && adduser -S -D -H -u 10001 -G ramals ramals
WORKDIR /app
COPY --from=build --chown=ramals:ramals /workspace/learning-platform/build/libs/learning-platform-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

