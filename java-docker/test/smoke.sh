#!/bin/sh
# Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
# Licensed under the Apache License, Version 2.0.
set -eu

# Retain the existing command as a thin alias to the shared Java runner.
directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java "$directory/SmokeTest.java" "$@"
