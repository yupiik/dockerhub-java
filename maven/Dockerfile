ARG BASE_IMAGE=ossyupiik/java-jdk:17.0.12@sha256:ddd008040b1805c9fc342ef9812ed1c7811fd950d765832250ea610607ca816a
ARG MAVEN_VERSION=3.9.9
ARG MAVEN_IMAGE=${MAVEN_VERSION}-eclipse-temurin-17@sha256:e260c430e6fc5296ed442eb6617ae11be9f5c5fc2f512c84df9e1a86e008e184

FROM maven:${MAVEN_IMAGE} AS mvn_image

FROM ${BASE_IMAGE}

RUN apk add --no-cache bash procps curl tar openssh-client git

# common for all images
LABEL org.opencontainers.image.title="Yupiik packaged Maven"
LABEL org.opencontainers.image.source=https://github.com/yupiik/dockerhub-java
LABEL org.opencontainers.image.url=https://github.com/yupiik/dockerhub-java
LABEL org.opencontainers.image.description="A Yupiik packaged Maven image with Git"

ENV MAVEN_HOME=/usr/share/maven

COPY --from=mvn_image ${MAVEN_HOME} ${MAVEN_HOME}
COPY --from=mvn_image /usr/local/bin/mvn-entrypoint.sh /usr/local/bin/mvn-entrypoint.sh
COPY --from=mvn_image /usr/share/maven/ref/settings-docker.xml /usr/share/maven/ref/settings-docker.xml

ARG MAVEN_VERSION
ARG USER_HOME_DIR="/root"
RUN ln -s ${MAVEN_HOME}/bin/mvn /usr/bin/mvn


ENV MAVEN_CONFIG="$USER_HOME_DIR/.m2"

ENTRYPOINT ["/usr/local/bin/mvn-entrypoint.sh"]
CMD ["mvn"]
