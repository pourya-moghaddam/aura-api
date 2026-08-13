# syntax=docker/dockerfile:1.7
#
# One Dockerfile for every service, selected with --build-arg SERVICE=<module>.
#
#   docker build --build-arg SERVICE=auth-service -t aura/auth-service:dev .
#
# Eight near-identical Dockerfiles would drift: someone bumps a base image or adds a JVM flag in
# one and not the others, and the difference is invisible until a service behaves oddly in prod.
#
# The BuildKit cache mount on ~/.m2 is what makes this cheap. Without it, each of the eight builds
# re-downloads the full dependency tree; with it they share one cache and only the first pays.

ARG JAVA_VERSION=25

# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build

ARG SERVICE
WORKDIR /build

# The Maven wrapper pins the Maven version, and means the build does not depend on a `maven:`
# image tag existing for this JDK - the official Maven images lag new JDK releases.
COPY mvnw ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw

# Every module's pom, even ones this image does not build. Maven reads the whole reactor before
# it applies -pl, so a module listed in the parent pom whose directory is missing is a hard error
# ("Child module /build/media-service of /build/pom.xml does not exist"). Poms are tiny and
# change rarely, so this also makes a stable layer to resolve dependencies against.
#
# !! THIS LIST MUST MATCH <modules> IN pom.xml !!
# Adding a module to the parent without adding it here breaks *every* image build, not just the
# new module's, and the Maven error names the missing directory rather than this file. The guard
# below turns that into an actionable message instead of a confusing one.
COPY pom.xml ./
COPY common-events/pom.xml common-events/
COPY common-web/pom.xml common-web/
COPY common-security/pom.xml common-security/
COPY auth-service/pom.xml auth-service/
COPY catalog-service/pom.xml catalog-service/
COPY order-service/pom.xml order-service/
COPY media-service/pom.xml media-service/
COPY notification-service/pom.xml notification-service/
COPY discovery-service/pom.xml discovery-service/
COPY gateway-service/pom.xml gateway-service/

# Fails fast, and says exactly what to do, when the list above drifts from the parent pom.
RUN set -eu; \
    missing=""; \
    for module in $(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' pom.xml); do \
        [ -f "$module/pom.xml" ] || missing="$missing $module"; \
    done; \
    if [ -n "$missing" ]; then \
        echo "ERROR: pom.xml declares modules with no COPY line in the Dockerfile:$missing"; \
        echo "Add 'COPY <module>/pom.xml <module>/' above."; \
        exit 1; \
    fi

# Sources for the target module and the shared libraries only. A change to catalog-service does
# not invalidate this layer in the auth-service image.
COPY common-events/src common-events/src
COPY common-web/src common-web/src
COPY common-security/src common-security/src
COPY ${SERVICE}/src ${SERVICE}/src

# -am builds the module and only the internal modules it depends on, not the whole reactor.
#
# Retried because a single `Connection reset` from Maven Central fails the whole build, and on a
# slow or unstable link that is close to inevitable - one run here died after eight minutes with
# five artifacts left to fetch. Each attempt resumes rather than restarts: the cache mount keeps
# every artifact already downloaded, so retry N only fetches what retry N-1 missed.
#
# The retry wraps the whole command rather than setting resolver retry properties, because which
# property applies depends on the transport Maven picked (wagon vs. the native resolver), and
# getting that wrong fails silently by simply not retrying.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    for attempt in 1 2 3 4 5; do \
        echo "=== Maven attempt ${attempt} ==="; \
        ./mvnw -B -pl ${SERVICE} -am -DskipTests package && exit 0; \
        echo "=== attempt ${attempt} failed, retrying ==="; \
        sleep 10; \
    done; \
    echo "Maven failed after 5 attempts"; \
    exit 1

# Split the fat jar into a rarely-changing lib/ directory and the small application jar, so a code
# change does not invalidate the dependency layer on every rebuild.
RUN set -eux; \
    jar="$(find ${SERVICE}/target -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -1)"; \
    java -Djarmode=tools -jar "$jar" extract --destination /build/extracted; \
    mv /build/extracted/*.jar /build/extracted/app.jar

# ---------------------------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

# curl is needed by the Compose healthcheck; without it HEALTHCHECK silently reports unhealthy
# and every dependent service hangs on `condition: service_healthy`.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root. A container escape from an app running as uid 0 starts as root on the host.
RUN groupadd --system --gid 1001 aura \
    && useradd --system --uid 1001 --gid aura --home /app aura

WORKDIR /app

# Dependencies first: this layer changes only when the dependency set does.
COPY --from=build --chown=aura:aura /build/extracted/lib/ ./lib/
COPY --from=build --chown=aura:aura /build/extracted/app.jar ./app.jar

USER aura

EXPOSE 8080

# MaxRAMPercentage rather than -Xmx: the JVM then tracks the container's cgroup limit instead of a
# number that has to be kept in sync with Compose by hand.
# ExitOnOutOfMemoryError makes the orchestrator restart a wedged process rather than leaving it up
# and failing every request.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
