#!/bin/sh
# Creates the local signing key used for release builds (once). Keep keystore/ safe:
# Android only accepts updates signed with the same key. keystore/ is git-ignored.
# Usage: AURA_KEY_PASSWORD=... tools/make-keystore.sh
set -e
cd "$(dirname "$0")/.."
mkdir -p keystore
[ -f keystore/aura-release.jks ] && { echo "keystore/aura-release.jks already exists"; exit 0; }
[ -n "$AURA_KEY_PASSWORD" ] || { echo "Set AURA_KEY_PASSWORD first"; exit 1; }
keytool -genkeypair -v -keystore keystore/aura-release.jks -alias aura \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$AURA_KEY_PASSWORD" -keypass "$AURA_KEY_PASSWORD" \
  -dname "CN=Aura TV, O=Aura TV Local Build"
umask 077
printf 'storePassword=%s\nkeyAlias=aura\nkeyPassword=%s\n' "$AURA_KEY_PASSWORD" "$AURA_KEY_PASSWORD" > keystore/signing.properties
echo "Wrote keystore/aura-release.jks and keystore/signing.properties"
