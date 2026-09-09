#!/usr/bin/env bash
# =============================================================================
# stop-all.sh — para lo que levantó start-all.sh.
#
#   ./scripts/stop-all.sh              # para apps + mock SAP + contenedores
#   ./scripts/stop-all.sh --apps-only  # solo las apps Java
#   ./scripts/stop-all.sh --purge      # además borra volúmenes (reset de datos)
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

APPS_ONLY=0
PURGE=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apps-only) APPS_ONLY=1; shift ;;
        --purge)     PURGE=1; shift ;;
        -h|--help)   awk 'NR>1 && /^#/ {print; next} NR>1 {exit}' "$0"; exit 0 ;;
        *)           echo "Opción desconocida: $1" >&2; exit 2 ;;
    esac
done

step() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }

RUN_DIR="$REPO_ROOT/logs/run"

step "Parando las aplicaciones"
for pidfile in "$RUN_DIR"/*.pid; do
    [[ -e "$pidfile" ]] || { ok "no había apps registradas"; break; }
    pid="$(cat "$pidfile")"
    name="$(basename "$pidfile" .pid)"
    if kill -0 "$pid" 2>/dev/null; then
        # spring-boot:run arranca la app como hijo de Maven: se mata el grupo.
        pkill -TERM -P "$pid" 2>/dev/null || true
        kill -TERM "$pid" 2>/dev/null || true
        ok "${name}-app parada (pid $pid)"
    else
        ok "${name}-app ya no corría"
    fi
    rm -f "$pidfile"
done

if (( APPS_ONLY )); then
    exit 0
fi

step "Parando el SAP simulado"
if docker ps --format '{{.Names}}' | grep -qx mock-sap; then
    docker stop mock-sap >/dev/null
    ok "mock-sap parado"
else
    ok "mock-sap no estaba levantado"
fi

step "Parando los external-services"
if (( PURGE )); then
    docker compose -f external-services/docker-compose.yml down -v
    ok "contenedores y volúmenes eliminados (los seeds se recrearán al reiniciar)"
else
    docker compose -f external-services/docker-compose.yml down
    ok "contenedores parados (los datos se conservan en los volúmenes)"
fi
