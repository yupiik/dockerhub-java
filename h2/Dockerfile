#
# Build:
# $ docker build --build-arg h2Version=2.1.214 . -t yupiik/h2:2.1.214
#
# Usage:
# $ docker run yupiik/h2:2.1.214 -tcp -tcpAllowOthers -tcpPort 9092 -tcpPassword secret -baseDir /opt/storage/h2 [-ifNotExists]
#
# Client URL: jdbc:h2:tcp://172.17.0.2:2000/<database>
#
ARG h2Version=2.1.214
ARG alpineVersion=3.16.1
FROM alpine:$alpineVersion as builder
ARG h2Version

RUN wget --quiet "https://repo1.maven.org/maven2/com/h2database/h2/$h2Version/h2-$h2Version.jar" -O /opt/h2.jar

FROM ossyupiik/java:17.0.4@sha256:9893079a3f27547925c10c82532c6dc431d9584c62cb2d5f24c00b786d3c096b
COPY --from=builder /opt/h2.jar /opt/h2/h2.jar
ENTRYPOINT [ "/opt/jre/bin/java", "-Djava.awt.headless=true", "-cp", "/opt/h2/h2.jar", "org.h2.tools.Server" ]
CMD [ "-?" ]
