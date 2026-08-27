#!/bin/sh
set -eu

MAVEN_VERSION="3.9.16"
MAVEN_BASE="${MAVEN_USER_HOME:-${HOME}/.m2}"
MAVEN_ROOT="${MAVEN_BASE}/wrapper/dists/cyfuture-maven-${MAVEN_VERSION}"
MAVEN_HOME="${MAVEN_ROOT}/apache-maven-${MAVEN_VERSION}"
MAVEN_BIN="${MAVEN_HOME}/bin/mvn"
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
MAVEN_SHA512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"

if [ ! -x "$MAVEN_BIN" ]; then
  mkdir -p "$MAVEN_ROOT"
  ARCHIVE="${MAVEN_ROOT}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  echo "Maven is not installed globally. Downloading Apache Maven ${MAVEN_VERSION} once..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$MAVEN_URL" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "$MAVEN_URL"
  else
    echo "curl or wget is required for the first Maven Wrapper run" >&2
    exit 1
  fi
  ACTUAL_SHA512="$(sha512sum "$ARCHIVE" | awk '{print $1}')"
  if [ "$ACTUAL_SHA512" != "$MAVEN_SHA512" ]; then
    rm -f "$ARCHIVE"
    echo "Maven download checksum verification failed" >&2
    exit 1
  fi
  tar -xzf "$ARCHIVE" -C "$MAVEN_ROOT"
  rm -f "$ARCHIVE"
fi

exec "$MAVEN_BIN" "$@"
