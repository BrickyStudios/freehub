#!/usr/bin/env bash
# One-time: hands the release key from ~/.config/schaltblick to GitHub Actions, so tags build signed APKs.
set -euo pipefail
R=BrickyStudios/schaltblick
base64 -w0 ~/.config/schaltblick/release.jks | gh secret set KEYSTORE_BASE64 -R "$R"
set -a; . ~/.config/schaltblick/signing.env; set +a
for k in SIGNING_KEY_ALIAS SIGNING_KEY_PASSWORD SIGNING_STORE_PASSWORD; do printf '%s' "${!k}" | gh secret set "$k" -R "$R"; done
gh secret list -R "$R"
