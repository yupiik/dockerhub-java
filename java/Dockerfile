ARG DISTRIBUTION=zulu17.52.17-ca-jre17.0.12-linux_musl_x64
ARG ALPINE_VERSION=3.20.2
ARG WORK_DIR='/opt/jre'

FROM alpine:$ALPINE_VERSION as builder
ARG DISTRIBUTION
ARG WORK_DIR

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
RUN wget --quiet "https://cdn.azul.com/zulu/bin/${DISTRIBUTION}.tar.gz" && \
    mkdir -p ${WORK_DIR} && \
    tar xf "${DISTRIBUTION}.tar.gz" --strip 1 -C ${WORK_DIR}

# scratch would work but will prevent temp files to work so let's consume +2M but have it functional
FROM alpine:$ALPINE_VERSION

ARG WORK_DIR

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8' JAVA_HOME=${WORK_DIR}
ENV PATH="${WORK_DIR}/bin:${PATH}"

COPY --from=builder ${WORK_DIR} ${WORK_DIR}

CMD [ "java", "--version" ]
