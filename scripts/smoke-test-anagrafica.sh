#!/usr/bin/env bash
# ============================================================
# smoke-test-anagrafica.sh
# Verifica manuale rapida degli endpoint anagrafica.
# Richiede: docker, curl, python3 + cryptography
#           (pip install cryptography)
# Uso: ./scripts/smoke-test-anagrafica.sh
# ============================================================
set -euo pipefail

BASE_URL="http://localhost:8080"
ENV_FILE="$(dirname "$0")/../.env"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
PASS=0; FAIL=0

ok()   { echo -e "${GREEN}✓ $1${NC}"; (( ++PASS )); }
fail() { echo -e "${RED}✗ $1${NC}";   (( ++FAIL )); }
info() { echo -e "${YELLOW}▶ $1${NC}"; }

# ── Prerequisiti ────────────────────────────────────────────

check_prereqs() {
    info "Controllo prerequisiti..."
    command -v docker  >/dev/null || { fail "docker non trovato"; exit 1; }
    command -v curl    >/dev/null || { fail "curl non trovato";   exit 1; }
    command -v python3 >/dev/null || { fail "python3 non trovato"; exit 1; }
    python3 -c "from cryptography.hazmat.primitives import hashes" 2>/dev/null \
        || { fail "cryptography non installato: pip install cryptography"; exit 1; }
    ok "Prerequisiti OK"
}

# ── Docker Compose ──────────────────────────────────────────

start_docker() {
    info "Avvio Docker Compose (PostgreSQL)..."
    docker compose -f "$(dirname "$0")/../docker-compose.yml" up -d --quiet-pull
    echo -n "  Attendo che PostgreSQL sia healthy"
    for i in $(seq 1 30); do
        if docker inspect agos-postgres --format='{{.State.Health.Status}}' 2>/dev/null | grep -q healthy; then
            echo " "; ok "PostgreSQL pronto"; return; fi
        echo -n "."; sleep 2
    done
    fail "PostgreSQL non si avvia in tempo"
    exit 1
}

# ── Quarkus ─────────────────────────────────────────────────

wait_quarkus() {
    info "Attendo Quarkus su $BASE_URL/q/health ..."
    for i in $(seq 1 40); do
        if curl -sf "$BASE_URL/q/health/live" >/dev/null 2>&1; then
            ok "Quarkus risponde"; return; fi
        echo -n "."; sleep 3
    done
    echo ""
    fail "Quarkus non si avvia. Avvialo con: ./mvnw quarkus:dev"
    exit 1
}

# ── Generazione JWT ─────────────────────────────────────────

generate_jwt() {
    local role="${1:-ADMIN}"
    source "$ENV_FILE" 2>/dev/null || true
    python3 - "$BASE64_PRIVATE_KEY" "$role" <<'PYEOF'
import sys, base64, json, time, uuid
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding as asym_padding

b64_key = sys.argv[1]
role    = sys.argv[2]

key_bytes   = base64.b64decode(b64_key)
private_key = serialization.load_der_private_key(key_bytes, password=None)

now = int(time.time())
header  = base64.urlsafe_b64encode(json.dumps({"alg":"RS256","typ":"JWT"}).encode()).rstrip(b'=').decode()
payload = base64.urlsafe_b64encode(json.dumps({
    "iss":    "https://agostinelli.gestionale",
    "sub":    str(uuid.uuid4()),
    "email":  "smoke-test@agostinelli.it",
    "role":   role,
    "groups": [role],
    "iat":    now,
    "exp":    now + 3600
}).encode()).rstrip(b'=').decode()

message   = f"{header}.{payload}".encode()
signature = private_key.sign(message, asym_padding.PKCS1v15(), hashes.SHA256())
sig_b64   = base64.urlsafe_b64encode(signature).rstrip(b'=').decode()
print(f"{header}.{payload}.{sig_b64}")
PYEOF
}

# ── Helpers curl ────────────────────────────────────────────

req() {
    local method="$1" path="$2" token="$3"
    shift 3
    curl -sf -X "$method" "$BASE_URL$path" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" \
        "$@" 2>&1
}

check() {
    local desc="$1" method="$2" path="$3" token="$4" expected_status="$5"
    shift 5
    local http_out status body
    http_out=$(curl -s -o /tmp/smoke_body -w "%{http_code}" \
        -X "$method" "$BASE_URL$path" \
        -H "Authorization: Bearer $token" \
        -H "Content-Type: application/json" "$@" 2>&1) || true
    status="$http_out"
    body=$(cat /tmp/smoke_body 2>/dev/null || true)

    if [ "$status" = "$expected_status" ]; then
        ok "$desc → HTTP $status"
    else
        fail "$desc → atteso $expected_status, ricevuto $status | $body"
    fi
}

check_no_auth() {
    local desc="$1" method="$2" path="$3" expected_status="$4"
    shift 4
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" -X "$method" "$BASE_URL$path" "$@")
    if [ "$status" = "$expected_status" ]; then
        ok "$desc (no-auth) → HTTP $status"
    else
        fail "$desc (no-auth) → atteso $expected_status, ricevuto $status"
    fi
}

# ── Main ────────────────────────────────────────────────────

main() {
    echo ""
    echo "══════════════════════════════════════════"
    echo "   Smoke Test – Modulo Anagrafica"
    echo "══════════════════════════════════════════"
    echo ""

    check_prereqs
    start_docker
    wait_quarkus

    info "Generazione JWT (ADMIN e DIPENDENTE)..."
    JWT_ADMIN=$(generate_jwt "ADMIN")
    JWT_DIPE=$(generate_jwt "DIPENDENTE")
    ok "JWT generati"
    echo ""

    # ── Business Unit ──────────────────────────────────────
    info "── Business Unit (/api/bu) ──"
    check "GET /api/bu (ADMIN)"      GET /api/bu "$JWT_ADMIN" 200
    check "GET /api/bu (DIPENDENTE)" GET /api/bu "$JWT_DIPE"  200
    check_no_auth "GET /api/bu senza token" GET /api/bu 401
    echo ""

    # ── Conti Bancari ──────────────────────────────────────
    info "── Conti Bancari (/api/conti) ──"
    check "GET /api/conti" GET /api/conti "$JWT_ADMIN" 200
    echo ""

    # ── Categorie ──────────────────────────────────────────
    info "── Categorie (/api/categorie) ──"
    check_no_auth "GET /api/categorie senza token" GET "/api/categorie?tipo=ENTRATA&buId=1" 401
    check "GET /api/categorie senza buId → 400" GET "/api/categorie" "$JWT_ADMIN" 400
    check "GET /api/categorie BU1 ENTRATA"  GET "/api/categorie?tipo=ENTRATA&buId=1"  "$JWT_ADMIN" 200
    check "GET /api/categorie BU1 USCITA"   GET "/api/categorie?tipo=USCITA&buId=1"   "$JWT_ADMIN" 200

    # Crea categoria
    CAT_BODY='{"nome":"Smoke Test Cat","tipo":"ENTRATA","buId":1,"ordinamento":99}'
    check "POST /api/categorie (ADMIN)"      POST /api/categorie "$JWT_ADMIN" 201 -d "$CAT_BODY"
    check "POST /api/categorie (DIPENDENTE → 403)" POST /api/categorie "$JWT_DIPE" 403 -d "$CAT_BODY"
    echo ""

    # ── Fornitori ──────────────────────────────────────────
    info "── Fornitori (/api/fornitori) ──"
    check "GET /api/fornitori"                 GET  "/api/fornitori"             "$JWT_ADMIN" 200
    check "GET /api/fornitori?search=Pasini"   GET  "/api/fornitori?search=Pasini" "$JWT_ADMIN" 200
    check "GET /api/fornitori?size=9999 → 200" GET  "/api/fornitori?size=9999"   "$JWT_ADMIN" 200
    check "GET /api/fornitori/{uuid-inesist} → 404" GET \
        "/api/fornitori/00000000-0000-0000-0000-000000000000" "$JWT_ADMIN" 404

    FORN_BODY='{"ragioneSociale":"Smoke Fornitore Test","piva":"99999999901"}'
    check "POST /api/fornitori"                POST /api/fornitori "$JWT_ADMIN" 201 -d "$FORN_BODY"
    check "POST /api/fornitori (DIPENDENTE→403)" POST /api/fornitori "$JWT_DIPE" 403 -d "$FORN_BODY"

    # Estrai UUID del fornitore appena creato
    FORN_ID=$(curl -sf -X POST "$BASE_URL/api/fornitori" \
        -H "Authorization: Bearer $JWT_ADMIN" \
        -H "Content-Type: application/json" \
        -d '{"ragioneSociale":"Smoke Alias Test","piva":"88888888801"}' 2>/dev/null \
        | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || true)

    if [ -n "$FORN_ID" ]; then
        ALIAS_BODY='{"pattern":"SMOKE TEST","matchType":"CONTAINS"}'
        check "POST alias fornitore"   POST "/api/fornitori/$FORN_ID/alias" "$JWT_ADMIN" 201 -d "$ALIAS_BODY"
        ALIAS_ID=$(curl -sf -X POST "$BASE_URL/api/fornitori/$FORN_ID/alias" \
            -H "Authorization: Bearer $JWT_ADMIN" \
            -H "Content-Type: application/json" \
            -d '{"pattern":"ALIAS DELETE TEST","matchType":"CONTAINS"}' 2>/dev/null \
            | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || true)
        [ -n "$ALIAS_ID" ] && check "DELETE alias" DELETE "/api/fornitori/$FORN_ID/alias/$ALIAS_ID" "$JWT_ADMIN" 204
    fi
    echo ""

    # ── Sommario ───────────────────────────────────────────
    echo "══════════════════════════════════════════"
    echo -e "  Risultati: ${GREEN}$PASS PASS${NC}  ${RED}$FAIL FAIL${NC}"
    echo "══════════════════════════════════════════"
    [ "$FAIL" -eq 0 ] && echo -e "${GREEN}✓ Tutti i test superati!${NC}" || echo -e "${RED}✗ $FAIL test falliti${NC}"
    echo ""
    [ "$FAIL" -eq 0 ] && exit 0 || exit 1
}

main "$@"
