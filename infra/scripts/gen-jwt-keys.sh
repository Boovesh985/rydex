#!/bin/bash
# Generates the RS256 key pair ride-service signs access tokens with.
#   infra/keys/jwt-private.pem  PKCS#8, keep secret (git-ignored)
#   infra/keys/jwt-public.pem   X.509, safe to share
# Without these files ride-service falls back to an ephemeral key (tokens die on restart).
set -euo pipefail

KEY_DIR="$(cd "$(dirname "$0")/.." && pwd)/keys"
mkdir -p "$KEY_DIR"

if [[ -f "$KEY_DIR/jwt-private.pem" && "${1:-}" != "--force" ]]; then
  echo "Keys already exist in $KEY_DIR (use --force to replace them; this signs everyone out)."
  exit 0
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY_DIR/jwt-private.pem"
openssl pkey -in "$KEY_DIR/jwt-private.pem" -pubout -out "$KEY_DIR/jwt-public.pem"
chmod 600 "$KEY_DIR/jwt-private.pem"
echo "Wrote $KEY_DIR/jwt-private.pem and $KEY_DIR/jwt-public.pem"
