#!/usr/bin/env bash
# Fails if credential material is present in a tracked file.
#
# Scope: exactly what `git ls-files` reports (build outputs and untracked scratch files are not
# shipped, so they cannot leak through the repository). The Messenger design allows exactly one
# public-looking JWT in the tree - the *anon* key in app/build.gradle.kts, which is public by
# design and guarded by RLS - so the JWT check decodes the payload and only fails on
# service_role / secret keys.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

status=0
fail() { printf '\033[31mFAIL\033[0m %s\n' "$*" >&2; status=1; }

mapfile -t files < <(git ls-files)

# 1. Private keys, keystores and known credential file names.
for f in "${files[@]}"; do
  case "$f" in
    *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|id_rsa*|id_ed25519*|.env|.env.*|*service-account*.json)
      fail "credential-shaped file is tracked: $f"
      ;;
  esac
done

# 2. Private key blocks anywhere in the tracked text files.
# Anchored at column 0 so that code which *mentions* or strips a PEM header (the FCM helper does)
# is not mistaken for an actual key block.
if grep -I -n -E -- "^-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----" "${files[@]}" 2>/dev/null | head -5; then
  fail "a PEM/PGP private key block is committed"
fi

# 3. SERVICE-ROLE (or other elevated) JWTs. Decode the payload of every candidate JWT.
python3 - <<'PY' || status=1
import base64, json, re, subprocess, sys

files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.split()
jwt = re.compile(rb"eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")
allowed_roles = {"anon", "authenticated"}  # shipped inside clients by design
bad = []
seen = 0
for name in files:
    try:
        with open(name, "rb") as fh:
            data = fh.read()
    except (OSError, IsADirectoryError):
        continue
    if b"\0" in data[:4096]:
        continue
    for match in jwt.finditer(data):
        seen += 1
        payload = match.group(0).split(b".")[1]
        payload += b"=" * (-len(payload) % 4)
        try:
            claims = json.loads(base64.urlsafe_b64decode(payload))
        except Exception:
            continue
        role = str(claims.get("role", ""))
        if role and role not in allowed_roles:
            bad.append(f"{name}: JWT with role '{role}'")
        if claims.get("iss") not in (None, "supabase") and role:
            bad.append(f"{name}: JWT with unexpected issuer {claims.get('iss')!r}")
print(f"scanned {seen} JWT(s) in tracked files")
if bad:
    for item in bad:
        print("::error::" + item)
    sys.exit(1)
PY

# 4. Cloud provider key shapes.
if grep -I -n -E -- "(AKIA[0-9A-Z]{16}|sk_live_[0-9a-zA-Z]{16,}|ghp_[0-9a-zA-Z]{30,}|xox[baprs]-[0-9a-zA-Z-]{10,})" \
     "${files[@]}" 2>/dev/null | head -5; then
  fail "a cloud/provider API key shape was found"
fi

if [[ $status -ne 0 ]]; then
  exit 1
fi
printf '\033[32mOK\033[0m no credential material in %d tracked files\n' "${#files[@]}"
