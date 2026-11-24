#!/usr/bin/env bash
# Creates the permanent release keystore and prints the commands that store it as GitHub secrets.
# Run it once, keep streaksaver-release.jks and the passwords somewhere safe (a password manager),
# and never commit the keystore. Losing it means users cannot update; they would have to reinstall.
set -euo pipefail

OUT="${1:-streaksaver-release.jks}"
ALIAS="${KEY_ALIAS:-streaksaver}"

if [ -e "$OUT" ]; then
  echo "Refusing to overwrite existing $OUT" >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "keytool not found (install a JDK)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found" >&2; exit 1; }

PASS="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
keytool -genkeypair -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Streak Saver RS, O=sxvuj"

echo
echo "Created $OUT"
echo "Alias:    $ALIAS"
echo "Password: $PASS   (same for the store and the key; write it down now)"
echo
echo "Add the secrets to the repository with the GitHub CLI:"
echo "  base64 -w0 $OUT | gh secret set KEYSTORE_BASE64 --repo sxvuj/tiktok-streak-saver-rs"
echo "  gh secret set KEYSTORE_PASSWORD --repo sxvuj/tiktok-streak-saver-rs --body '$PASS'"
echo "  gh secret set KEY_ALIAS         --repo sxvuj/tiktok-streak-saver-rs --body '$ALIAS'"
echo "  gh secret set KEY_PASSWORD      --repo sxvuj/tiktok-streak-saver-rs --body '$PASS'"
