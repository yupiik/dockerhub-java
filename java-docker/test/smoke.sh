#!/bin/sh
# Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
# Licensed under the Apache License, Version 2.0.
set -eu

image=${1:?Usage: sh java-docker/test/smoke.sh IMAGE [amd64|arm64]}
architecture=${2:-amd64}
directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
# mktemp is private by default; the container uses an unrelated non-root UID.
chmod 755 "$temporary"
javac --release 25 -d "$temporary" "$directory/SmokeProbe.java"

docker image inspect "$image" | python3 -c '
import json, sys
image = json.load(sys.stdin)[0]
config = image["Config"]
assert image["Os"] == "linux"
assert image["Architecture"] == sys.argv[1]
assert config["User"] == "10001:10001"
assert not config.get("Entrypoint")
assert config["Cmd"] == ["java", "--version"]
assert not config.get("Volumes")
assert not config.get("ExposedPorts")
environment = dict(value.split("=", 1) for value in config.get("Env", []))
assert environment["HOME"] == "/tmp"
assert not any(name.startswith("DOCKER_") for name in environment)
' "$architecture"

# No socket, elevated capabilities, root initialization or writable root.
docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges \
    --network none --pids-limit 128 --memory 256m --cpus 1 \
    --tmpfs /tmp:rw,nosuid,nodev,size=64m "$image" sh -ec '
    test "$(id -u)" = 10001
    test "$(id -g)" = 10001
    test "$(command -v docker)" = /usr/bin/docker
    test -s /etc/ssl/certs/ca-certificates.crt
    test ! -e /var/run/docker.sock
    test ! -e /var/lib/docker
    for daemon in dockerd containerd runc docker-compose docker-buildx; do
        if command -v "$daemon"; then
            echo "Unexpected daemon or plugin: $daemon" >&2
            exit 1
        fi
    done
    if touch /usr/bin/java-docker-smoke 2>/dev/null; then
        echo "Root filesystem is writable" >&2
        exit 1
    fi
    docker --version | grep "Docker version 29.9.2,"
    java --version
    '

docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges \
    --network none --pids-limit 128 --memory 256m --cpus 1 \
    --tmpfs /tmp:rw,nosuid,nodev,size=64m \
    --mount "type=bind,source=$temporary,target=/probe,readonly" \
    "$image" java -cp /probe SmokeProbe

# Explicit opt-in because this requires public network access.
if [ -n "${SMOKE_HTTPS_URL:-}" ]; then
    docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges \
        --pids-limit 128 --memory 256m --cpus 1 \
        --tmpfs /tmp:rw,nosuid,nodev,size=64m \
        --mount "type=bind,source=$temporary,target=/probe,readonly" \
        "$image" java -cp /probe SmokeProbe "$SMOKE_HTTPS_URL"
fi
