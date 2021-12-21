#
# Build:
# $ docker build --build-arg h2Version=1.4.200 . -t yupiik/h2:1.4.200
#
# Usage:
# $ docker run yupiik/h2:1.4.200 -tcp -tcpAllowOthers -tcpPort 9092 -tcpPassword secret -baseDir /opt/storage/h2 [-ifNotExists]
#
# Client URL: jdbc:h2:tcp://172.17.0.2:2000/<database>
#
ARG h2Version=1.4.200
ARG alpineVersion=3.15.0
FROM alpine:$alpineVersion as builder
ARG h2Version

RUN wget --quiet "https://repo1.maven.org/maven2/com/h2database/h2/$h2Version/h2-$h2Version.jar" -O /opt/h2.jar

FROM ossyupiik/java:17.0.1@sha256:5e8040466437f8b04e8f08a26e65b80142b130d0652fd75fe28d8a19416f36c5
COPY --from=builder /opt/h2.jar /opt/h2/h2.jar
ENTRYPOINT [ "/opt/jre/bin/java", "-Djava.awt.headless=true", "-cp", "/opt/h2/h2.jar", "org.h2.tools.Server" ]
CMD [ "-?" ]
