ARG DISTRIBUTION=zulu21.36.17-ca-jdk21.0.4-linux_musl_x64
ARG ALPINE_VERSION=3.20.3

FROM alpine:${ALPINE_VERSION} as builder
ARG DISTRIBUTION

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
RUN wget --quiet "https://cdn.azul.com/zulu/bin/${DISTRIBUTION}.tar.gz" && \
    mkdir -p /opt/jdk && \
    tar xf "${DISTRIBUTION}.tar.gz" --strip 1 -C /opt/jdk

# scratch would work but will prevent temp files to work so let's consume +2M but have it functional
FROM alpine:${ALPINE_VERSION}

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8' JAVA_HOME=/opt/jdk
ENV PATH="/opt/jre/bin:${PATH}"

COPY --from=builder /opt/jdk /opt/jdk

CMD [ "/opt/jdk/bin/java", "--version" ]
