#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

mkdir -p lib/pigeon android/app/src/main/java/io/mosip/registration/liveness/pigeon

dart run pigeon \
  --input pigeon/liveness.dart \
  --dart_out lib/pigeon/liveness.dart \
  --java_out android/app/src/main/java/io/mosip/registration/liveness/pigeon/Liveness.java \
  --java_package io.mosip.registration.liveness.pigeon
