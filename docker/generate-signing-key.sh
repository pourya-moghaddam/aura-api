#!/usr/bin/env bash
#
# Generates the RSA keypair auth-service signs tokens with, printed as .env-ready lines.
#
#   ./docker/generate-signing-key.sh >> .env
#
# Emits bare single-line base64 DER rather than armoured PEM. A .env value cannot contain real
# newlines, and the usual workaround -- writing literal \n escapes -- does not survive: nothing in
# the chain unescapes them, so they arrive in the JVM as backslash-n characters sitting in the
# middle of the base64 and decoding fails. One line of pure base64 sidesteps that entirely.
#
# The output is a private key. Treat the .env file accordingly.

set -euo pipefail

if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl is required" >&2
    exit 1
fi

tmpdir="$(mktemp -d)"
chmod 700 "$tmpdir"
trap 'rm -rf "$tmpdir"' EXIT

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmpdir/private.pem" 2>/dev/null

# base64 -w0 is GNU; BSD/macOS base64 has no -w flag and never wraps, so strip newlines either way.
b64() {
    base64 < "$1" | tr -d '\n'
}

# PKCS#8 DER, which is what java.security.spec.PKCS8EncodedKeySpec expects.
openssl pkey -in "$tmpdir/private.pem" -outform DER -out "$tmpdir/private.der" 2>/dev/null
# X.509 SubjectPublicKeyInfo DER, which is what X509EncodedKeySpec expects.
openssl rsa -in "$tmpdir/private.pem" -pubout -outform DER -out "$tmpdir/public.der" 2>/dev/null

echo "AURA_AUTH_TOKEN_RSA_PRIVATE_KEY_PEM=$(b64 "$tmpdir/private.der")"
echo "AURA_AUTH_TOKEN_RSA_PUBLIC_KEY_PEM=$(b64 "$tmpdir/public.der")"
echo "AURA_AUTH_TOKEN_RSA_KEY_ID=aura-$(date +%Y%m%d)"
