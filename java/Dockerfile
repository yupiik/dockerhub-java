ARG DISTRIBUTION=zulu17.40.19-ca-jre17.0.6-linux_musl_x64
ARG ALPINE_VERSION=3.17.2

FROM alpine:$ALPINE_VERSION as builder
ARG DISTRIBUTION

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
RUN wget --quiet "https://cdn.azul.com/zulu/bin/${DISTRIBUTION}.tar.gz" && \
    mkdir -p /opt/jre && \
    tar xf "${DISTRIBUTION}.tar.gz" --strip 1 -C /opt/jre

# scratch would work but will prevent temp files to work so let's consume +2M but have it funcitonal
FROM alpine:$ALPINE_VERSION

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8' JAVA_HOME=/opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

COPY --from=builder /opt/jre /opt/jre

CMD [ "/opt/jre/bin/java", "--version" ]
