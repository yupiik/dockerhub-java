ARG BASE_IMAGE=ossyupiik/java-jdk:21.0.4@sha256:7fdb8988e8b1608ce5c834a89246ac3baeea2179befff154e5b94e1a3f402e4f
ARG MAVEN_VERSION=3.9.9
ARG MAVEN_IMAGE=${MAVEN_VERSION}-eclipse-temurin-21@sha256:d98e229685654e7c38a2f121bdf3766505c7fe6841284e45d46ed0411cd480a1

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
