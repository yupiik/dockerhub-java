ARG DOCKER_IMAGE=docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb
ARG JAVA_IMAGE=eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918

FROM ${DOCKER_IMAGE} AS docker-cli
FROM ${JAVA_IMAGE}

# Both source indexes select the target platform automatically.
# Copy only the static client, not dockerd, plugins or Docker's entrypoint.
COPY --from=docker-cli /usr/local/bin/docker /usr/bin/docker

LABEL org.opencontainers.image.vendor="Yupiik" \
      org.opencontainers.image.description="Java 25 runtime with Docker CLI only; no Docker daemon"

ENV HOME=/tmp
USER 10001:10001
WORKDIR /tmp

# Do not run Temurin's certificate-modifying entrypoint on a read-only root.
# The image already contains the upstream system and Java CA stores.
ENTRYPOINT []
CMD ["java", "--version"]
