ARG BASE_IMAGE
ARG MAVEN_VERSION=3.9.9
ARG MAVEN_IMAGE=${MAVEN_VERSION}-eclipse-temurin-17

FROM maven:${MAVEN_IMAGE} as mvnImage

FROM ${BASE_IMAGE}

RUN apk add --no-cache bash procps curl tar openssh-client git

# common for all images
LABEL org.opencontainers.image.title="Apache Maven"
LABEL org.opencontainers.image.source=https://github.com/carlossg/docker-maven
LABEL org.opencontainers.image.url=https://github.com/carlossg/docker-maven
LABEL org.opencontainers.image.description="Apache Maven is a software project management and comprehension tool. Based on the concept of a project object model (POM), Maven can manage a project's build, reporting and documentation from a central piece of information."

ENV MAVEN_HOME=/usr/share/maven

COPY --from=mvnImage ${MAVEN_HOME} ${MAVEN_HOME}
COPY --from=mvnImage /usr/local/bin/mvn-entrypoint.sh /usr/local/bin/mvn-entrypoint.sh
COPY --from=mvnImage /usr/share/maven/ref/settings-docker.xml /usr/share/maven/ref/settings-docker.xml

ARG MAVEN_VERSION
ARG USER_HOME_DIR="/root"
RUN ln -s ${MAVEN_HOME}/bin/mvn /usr/bin/mvn


ENV MAVEN_CONFIG="$USER_HOME_DIR/.m2"

ENTRYPOINT ["/usr/local/bin/mvn-entrypoint.sh"]
CMD ["mvn"]
