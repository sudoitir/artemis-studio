# Sign in to Studio over its API the way the scripts need to. Sourced, not run.
#
# The built-in ADMIN role requires a second factor (ADR-0143), so on a fresh stack a script has to change the
# one-time password, enrol an authenticator app and confirm it before it can do anything; on every later run
# it signs in with the password and a code computed from ADMIN_TOTP_SECRET, the secret the first run printed.
#
# Needs STUDIO, COOKIES (a curl cookie jar) and ADMIN_USER, ADMIN_PASSWORD and, once enrolled, ADMIN_TOTP_SECRET.
# Provides csrf, api and studio_sign_in. Works sourced from bash (3.2 and later) and zsh, so no variable here is
# named path, status or options, which zsh ties to its own state.

_SIGNIN_LIB=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)

# Studio protects mutating calls with the double-submit CSRF cookie a browser sends by itself; curl echoes it by hand.
csrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIES" | tail -1; }

# api METHOD PATH [curl args...] → response body on stdout
api() {
  local method=$1 endpoint=$2
  shift 2
  curl -sS -b "$COOKIES" -c "$COOKIES" -X "$method" "$STUDIO/api/v1$endpoint" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(csrf)" "$@"
}

# The next code Studio has not seen; TOTP_LAST_STEP is the step of the one before.
_next_code() {
  local out
  out=$(python3 "$_SIGNIN_LIB/totp.py" "$ADMIN_TOTP_SECRET" "${TOTP_LAST_STEP:--1}") || return 1
  TOTP_LAST_STEP=${out%% *}
  TOTP_CODE=${out##* }
}

# Three tries: a code another process used within the same 30 s step is refused as a replay.
_second_step() {
  if [ -z "${ADMIN_TOTP_SECRET:-}" ]; then
    echo "$ADMIN_USER has two-step verification: set ADMIN_TOTP_SECRET to the secret the first run printed" >&2
    return 2
  fi
  local _
  for _ in 1 2 3; do
    _next_code || return 1
    api POST /auth/second-factor -d "{\"totpCode\":\"$TOTP_CODE\"}" | grep -q '"status":"AUTHENTICATED"' && return 0
  done
  return 1
}

# _login PASSWORD → 0 once the password (and, for an enrolled account, the code) was accepted; 2 when it stopped for a reason it has printed
_login() {
  local result
  result=$(api POST /auth/login -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$1\"}") || return 1
  case $result in
    *'"status":"AUTHENTICATED"'*) return 0 ;;
    *'"status":"SECOND_FACTOR_REQUIRED"'*) _second_step ;;
    *) return 1 ;;
  esac
}

# Enrol an authenticator app for the signed-in account and confirm it; the session it leaves is a full one.
_enrol_totp() {
  local started secret
  started=$(api POST /auth/mfa/totp) || return 1
  secret=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["secret"])' <<<"$started") || {
    echo "could not start two-step enrolment: $started" >&2
    return 1
  }
  ADMIN_TOTP_SECRET=$secret
  TOTP_LAST_STEP=-1
  _next_code || return 1
  if ! api POST /auth/mfa/totp/confirm -d "{\"code\":\"$TOTP_CODE\"}" | grep -q recoveryCodes; then
    echo "could not confirm two-step enrolment" >&2
    return 1
  fi
  export ADMIN_TOTP_SECRET
  echo "two-step verification is set up for $ADMIN_USER. Use ADMIN_TOTP_SECRET=$secret from now on." >&2
}

# studio_sign_in [NEW_PASSWORD [NAME]] → 0 when signed in with a full session.
# NEW_PASSWORD is what a one-time password becomes, and what an earlier run may already have changed it to;
# NAME is the variable that holds it, for the message when it is missing.
studio_sign_in() {
  local new=${1:-} name=${2:-NEW_PASSWORD} me http_code
  curl -sS -b "$COOKIES" -c "$COOKIES" "$STUDIO/api/v1/auth/me" >/dev/null || true # issues the CSRF cookie
  local password rc=1
  for password in "$ADMIN_PASSWORD" ${new:+"$new"}; do
    _login "$password" && rc=0 || rc=$?
    if [ "$rc" = 0 ]; then
      ADMIN_PASSWORD=$password
      break
    fi
    [ "$rc" = 2 ] && return 1
  done
  if [ "$rc" != 0 ]; then
    echo "sign-in failed: is ADMIN_PASSWORD the one just dev-up printed${new:+ (or $name, once changed)}?" >&2
    return 1
  fi

  me=$(api GET /auth/me) || return 1
  if grep -q '"mustChangePassword":true' <<<"$me"; then
    if [ -z "$new" ]; then
      echo "Studio requires the one-time admin password to be changed first." >&2
      echo "Rerun with $name=<a password you choose>, and use that password from then on." >&2
      return 1
    fi
    # The change re-establishes the session, so the cookies stay valid for the rest of the run.
    http_code=$(python3 -c 'import json,sys; print(json.dumps({"currentPassword": sys.argv[1], "newPassword": sys.argv[2]}))' \
        "$ADMIN_PASSWORD" "$new" \
      | api POST /auth/password --data-binary @- -o /dev/null -w '%{http_code}')
    if [ "$http_code" != 204 ]; then
      echo "changing the admin password failed with HTTP $http_code" >&2
      return 1
    fi
    ADMIN_PASSWORD=$new
    me=$(api GET /auth/me) || return 1
  fi

  if grep -q '"secondFactorEnrolmentRequired":true' <<<"$me"; then
    _enrol_totp || return 1
  fi
}
