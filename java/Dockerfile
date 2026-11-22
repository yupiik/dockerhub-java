# Required: alpine:exact-version@sha256:multi-architecture-index-digest.
ARG ALPINE_IMAGE

FROM ${ALPINE_IMAGE} AS builder
ARG ALPINE_IMAGE
ARG DISTRIBUTION_URL
ARG DISTRIBUTION_SHA256

# The caller selects the official musl JRE archive for the native architecture.
RUN set -eu; \
    : "${DISTRIBUTION_SHA256:?DISTRIBUTION_SHA256 is required}"; \
    printf '%s\n' "$ALPINE_IMAGE" | grep -Eq '^alpine:[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}$'; \
    printf '%s\n' "$DISTRIBUTION_URL" | grep -Eq '^https://cdn\.azul\.com/zulu/bin/zulu[0-9.]+-ca-jre[0-9.]+-linux_musl_(x64|aarch64)\.tar\.gz$'; \
    printf '%s\n' "$DISTRIBUTION_SHA256" | grep -Eq '^[0-9a-fA-F]{64}$'; \
    apk add --no-cache ca-certificates; \
    wget -O /tmp/jre.tar.gz "$DISTRIBUTION_URL"; \
    printf '%s  /tmp/jre.tar.gz\n' "$DISTRIBUTION_SHA256" | sha256sum -c -; \
    mkdir -p /opt/jre; \
    tar xzf /tmp/jre.tar.gz --strip-components=1 -C /opt/jre; \
    test ! -e /opt/jre/bin/javac

FROM ${ALPINE_IMAGE}

# libstdc++ also brings libgcc; zlib is needed by the native Java libraries.
RUN apk add --no-cache libstdc++ zlib

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8' JAVA_HOME=/opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

COPY --from=builder /opt/jre /opt/jre

CMD [ "/opt/jre/bin/java", "--version" ]
