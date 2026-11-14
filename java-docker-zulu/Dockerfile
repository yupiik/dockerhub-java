# Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
# Licensed under the Apache License, Version 2.0.
ARG DEBIAN_IMAGE=debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
ARG DOCKER_IMAGE=docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb

FROM ${DOCKER_IMAGE} AS docker-cli
FROM ${DEBIAN_IMAGE} AS runtime-base

# Snapshot metadata is signed by Debian; packages are fixed along with the base.
RUN rm -f /etc/apt/sources.list.d/debian.sources && \
    printf '%s\n' \
      'deb [check-valid-until=no] https://snapshot.debian.org/archive/debian/20261104T000000Z bookworm main' \
      'deb [check-valid-until=no] https://snapshot.debian.org/archive/debian-security/20261104T000000Z bookworm-security main' \
      > /etc/apt/sources.list
# The slim base has no TLS CA store yet. Bootstrap from its authenticated HTTP
# snapshot, then keep HTTPS for subsequent package operations.
RUN sed -i 's|https://snapshot|http://snapshot|g' /etc/apt/sources.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates libstdc++6 libfontconfig1 libfreetype6 zlib1g && \
    sed -i 's|http://snapshot|https://snapshot|g' /etc/apt/sources.list && \
    rm -rf /var/lib/apt/lists/*

FROM runtime-base AS jre
ARG TARGETARCH
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    case "$TARGETARCH" in \
      amd64) archive=zulu25.38.109-ca-jre25.0.5.0.3-linux_x64.tar.gz; \
             checksum=740a9a3ed6b60de7ae2c22633f122147572024c1a66663f69aa7672ec48c4b09 ;; \
      arm64) archive=zulu25.38.109-ca-jre25.0.5.0.3-linux_aarch64.tar.gz; \
             checksum=604d65f811f942d49099b7c1c1b865ff8b700d51299be0afffa24447a033b866 ;; \
      *) echo "Unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac && \
    curl --fail --location --retry 3 "https://cdn.azul.com/zulu/bin/$archive" -o /tmp/jre.tar.gz && \
    echo "$checksum  /tmp/jre.tar.gz" | sha256sum --check --strict && \
    mkdir /opt/jre && tar -xzf /tmp/jre.tar.gz --strip-components=1 -C /opt/jre && \
    rm /tmp/jre.tar.gz

FROM runtime-base
COPY --from=jre /opt/jre /opt/jre
# Only the static client; no daemon, plugins or upstream entrypoint.
COPY --from=docker-cli /usr/local/bin/docker /usr/bin/docker

LABEL org.opencontainers.image.vendor="Yupiik" \
      org.opencontainers.image.description="Azul Zulu Java 25 glibc runtime with Docker CLI only; no Docker daemon"
ENV JAVA_HOME=/opt/jre LANG=C.UTF-8 HOME=/tmp
ENV PATH="/opt/jre/bin:${PATH}"
USER 10001:10001
WORKDIR /tmp
ENTRYPOINT []
CMD ["java", "--version"]
