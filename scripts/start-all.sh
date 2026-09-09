#!/usr/bin/env bash
# =============================================================================
# start-all.sh — arranca el PoC completo con un solo comando.
#
#   ./scripts/start-all.sh                  # todo en local (contenedores + apps)
#   ./scripts/start-all.sh --env test       # apps en local contra servicios de test
#   ./scripts/start-all.sh --with-cdc       # además registra los conectores Debezium
#   ./scripts/start-all.sh --no-apps        # solo infraestructura
#   ./scripts/start-all.sh --skip-build     # no recompila el reactor
#
# Documentación: docs/QUICK_START.md (anexo "Arranque con un solo comando").
# Parada: ./scripts/stop-all.sh
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ENV_NAME="local"
WITH_CDC=0
START_APPS=1
SKIP_BUILD=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)        ENV_NAME="${2:?--env necesita un valor: local|test}"; shift 2 ;;
        --with-cdc)   WITH_CDC=1; shift ;;
        --no-apps)    START_APPS=0; shift ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        -h|--help)    awk 'NR>1 && /^#/ {print; next} NR>1 {exit}' "$0"; exit 0 ;;
        *)            echo "Opción desconocida: $1 (usa --help)" >&2; exit 2 ;;
    esac
done

if [[ "$ENV_NAME" != "local" && "$ENV_NAME" != "test" ]]; then
    echo "--env debe ser 'local' o 'test' (recibido: $ENV_NAME)" >&2
    exit 2
fi

LOG_DIR="$REPO_ROOT/logs"
RUN_DIR="$LOG_DIR/run"
mkdir -p "$RUN_DIR"

# --- salida legible -----------------------------------------------------------
step()  { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()    { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn()  { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
fail()  { printf '  \033[0;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || fail "falta '$1' en el PATH"; }

# --- carga de variables de entorno -------------------------------------------
ENV_FILE="$REPO_ROOT/scripts/env/${ENV_NAME}.env"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
    ok "variables cargadas de scripts/env/${ENV_NAME}.env"
elif [[ "$ENV_NAME" == "test" ]]; then
    fail "no existe scripts/env/test.env — cópialo de scripts/env/test.env.example y rellénalo"
fi

# --- endpoints que se muestran al usuario ------------------------------------
# Se derivan de las variables de entorno (con el default local), de modo que en
# modo test el inventario final muestra ya los hosts de test.
D_CUSTOMER_PORT="${CUSTOMER_SERVER_PORT:-8081}"
D_ARTICLE_PORT="${ARTICLE_SERVER_PORT:-8082}"
D_MOCK_SAP="http://localhost:${MOCK_SAP_PORT:-8090}"
D_SQLSERVER="${SQLSERVER_URL:-jdbc:sqlserver://localhost:1433;databaseName=poc;encrypt=true;trustServerCertificate=true}"
D_POSTGRES="${POSTGRES_URL:-jdbc:postgresql://localhost:5432/poc}"
D_MONGO_CUSTOMER="${MONGO_URL_CUSTOMER:-${MONGO_URL:-mongodb://localhost:27017/customer}}"
D_MONGO_ARTICLE="${MONGO_URL_ARTICLE:-${MONGO_URL:-mongodb://localhost:27017/article}}"
D_ES="${ES_URL:-http://localhost:9200}"
D_KAFKA="${KAFKA_BOOTSTRAP:-localhost:9092}"
D_CONNECT="${KAFKA_CONNECT_URL:-http://localhost:8083}"
D_KIBANA="${KIBANA_URL:-http://localhost:5601}"
D_MINIO_CONSOLE="${MINIO_CONSOLE_URL:-http://localhost:9001}"
D_MINIO_API="${MINIO_API_URL:-http://localhost:9000}"
D_MYSQL_SDD="${MYSQL_SDD_URL:-jdbc:mysql://localhost:3306/sdd_registry}"

# =============================================================================
# Esperas
# =============================================================================

# wait_healthy <contenedor> <timeout_s> [acceso] — espera a healthy (o running
# si el servicio no declara healthcheck) e imprime cómo llegar a él.
wait_healthy() {
    local name="$1" timeout="$2" access="${3:-}" waited=0 status
    printf '  … %-20s ' "$name"
    while true; do
        status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                  "$name" 2>/dev/null || echo missing)"
        case "$status" in
            healthy|running)
                printf '\033[0;32m%s\033[0m (%ss)\n' "$status" "$waited"
                [[ -n "$access" ]] && printf '      \033[0;36m→\033[0m %s\n' "$access"
                return 0 ;;
            exited|dead)     printf '\033[0;31m%s\033[0m\n' "$status"
                             fail "$name terminó inesperadamente — 'docker logs $name'" ;;
        esac
        if (( waited >= timeout )); then
            printf '\033[0;31mtimeout\033[0m tras %ss (estado: %s)\n' "$timeout" "$status"
            fail "$name no llegó a healthy — 'docker logs $name'"
        fi
        sleep 5; waited=$((waited + 5))
        (( waited % 30 == 0 )) && printf '%ss ' "$waited"
    done
}

# wait_exit_ok <contenedor> <timeout_s> — espera a que un contenedor one-shot
# (los *-init) termine con código 0.
wait_exit_ok() {
    local name="$1" timeout="$2" waited=0 state code
    printf '  … %-20s ' "$name"
    while true; do
        state="$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || echo missing)"
        if [[ "$state" == "exited" ]]; then
            code="$(docker inspect -f '{{.State.ExitCode}}' "$name")"
            if [[ "$code" == "0" ]]; then
                printf '\033[0;32mcompletado\033[0m (%ss)\n' "$waited"; return 0
            fi
            printf '\033[0;31mexit %s\033[0m\n' "$code"
            fail "$name falló — 'docker logs $name'"
        fi
        if (( waited >= timeout )); then
            printf '\033[0;31mtimeout\033[0m tras %ss\n' "$timeout"
            fail "$name no terminó — 'docker logs $name'"
        fi
        sleep 5; waited=$((waited + 5))
        (( waited % 30 == 0 )) && printf '%ss ' "$waited"
    done
}

# wait_http <url> <timeout_s> <etiqueta> — espera a que una URL responda 2xx/3xx.
wait_http() {
    local url="$1" timeout="$2" label="$3" waited=0
    printf '  … %-20s ' "$label"
    while ! curl -fsS -o /dev/null --max-time 5 "$url" 2>/dev/null; do
        if (( waited >= timeout )); then
            printf '\033[0;31mtimeout\033[0m tras %ss\n' "$timeout"
            fail "$label no responde en $url"
        fi
        sleep 5; waited=$((waited + 5))
        (( waited % 30 == 0 )) && printf '%ss ' "$waited"
    done
    printf '\033[0;32mUP\033[0m (%ss)\n' "$waited"
    return 0
}

# =============================================================================
# 1. Requisitos
# =============================================================================
step "Comprobando requisitos"
need curl
need mvn
command -v jq >/dev/null 2>&1 || warn "jq no instalado (opcional, mejora la salida de los curl)"
if [[ "$ENV_NAME" == "local" ]]; then
    need docker
    docker info >/dev/null 2>&1 || fail "el daemon de Docker no responde"
fi
ok "java $(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
ok "entorno: $ENV_NAME"

# =============================================================================
# 2. Infraestructura
# =============================================================================
if [[ "$ENV_NAME" == "local" ]]; then
    step "Levantando external-services (docker compose)"
    docker compose -f external-services/docker-compose.yml up -d
    ok "contenedores creados; esperando a que estén sanos"

    # Los timeouts salen de los healthcheck del compose. SQL Server es el lento:
    # start_period 600s porque el primer arranque hace un upgrade interno de msdb.
    wait_healthy postgres-source  120 "$D_POSTGRES  ·  postgres / postgres"
    wait_healthy mongodb          120 "$D_MONGO_CUSTOMER  ·  $D_MONGO_ARTICLE  ·  sin auth"
    wait_healthy kafka-broker     180 "$D_KAFKA  ·  topics outbox.CUSTOMER / outbox.ARTICLE"
    wait_healthy elasticsearch    240 "$D_ES"
    wait_healthy minio            120 "consola $D_MINIO_CONSOLE  ·  minioadmin / minioadmin123"
    wait_healthy mysql-sdd        180 "$D_MYSQL_SDD  ·  sdd / sdd  ·  registro de features SDD"
    wait_healthy sqlserver-source 900 "$D_SQLSERVER  ·  sa / SqlServer_Pa55w0rd!"
    wait_exit_ok sqlserver-init   300   # DDL + seed del legacy customer
    wait_exit_ok minio-init       120
    wait_healthy kafka-connect    420 "$D_CONNECT/connectors"
    wait_healthy kibana           300 "$D_KIBANA  ·  Dev Tools para consultar los índices"

    step "Levantando SAP simulado (WireMock)"
    if docker ps --format '{{.Names}}' | grep -qx mock-sap; then
        ok "mock-sap ya estaba levantado"
    else
        docker run --rm -d -p "${MOCK_SAP_PORT:-8090}:8080" --name mock-sap \
            wiremock/wiremock:3.13.0 >/dev/null
        ok "contenedor mock-sap creado"
    fi
    wait_http "http://localhost:${MOCK_SAP_PORT:-8090}/__admin/mappings" 60 "mock-sap"

    # Stub catch-all: cualquier método/ruta responde 201. Prioridad baja (10)
    # para poder añadir encima stubs más específicos.
    curl -fsS -X POST "http://localhost:${MOCK_SAP_PORT:-8090}/__admin/mappings" \
        -H 'Content-Type: application/json' \
        -d '{"priority":10,"request":{"method":"ANY","urlPattern":".*"},
             "response":{"status":201,"jsonBody":{"ok":true}}}' >/dev/null
    ok "stub catch-all registrado (201 a cualquier petición)"
else
    step "Entorno 'test': no se levantan contenedores"
    warn "las apps se conectarán a los servicios de test definidos en scripts/env/test.env"
    [[ -n "${ES_URL:-}" ]]        && wait_http "${ES_URL}/_cluster/health" 60 "elasticsearch"
    [[ -n "${SAP_S4_BASE_URL:-}" ]] && ok "SAP S/4 → ${SAP_S4_BASE_URL}"
    [[ -n "${SAP_BTP_BASE_URL:-}" ]] && ok "SAP BTP → ${SAP_BTP_BASE_URL}"
fi

# =============================================================================
# 3. Conectores CDC (opcional)
# =============================================================================
if (( WITH_CDC )); then
    step "Registrando conectores Debezium"
    CONNECT_URL="${KAFKA_CONNECT_URL:-http://localhost:8083}"
    for cfg in register-sqlserver-customer register-postgres-article; do
        file="external-services/debezium/${cfg}.json"
        [[ -f "$file" ]] || fail "no existe $file"
        # Nombre real del conector (no el del fichero), sin depender de jq: es lo
        # que devuelve /connectors y lo que hace idempotente el registro.
        name="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$file" | head -1)"
        [[ -n "$name" ]] || fail "no se pudo leer el nombre del conector en $file"
        if curl -fsS "${CONNECT_URL}/connectors" | grep -q "\"${name}\""; then
            ok "$name ya registrado"
        else
            curl -fsS -X POST "${CONNECT_URL}/connectors/" \
                -H 'Content-Type: application/json' -d @"$file" >/dev/null
            ok "$name registrado"
        fi
    done
    sleep 10   # el conector necesita un momento para pasar a RUNNING
    curl -fsS "${CONNECT_URL}/connectors?expand=status" \
        | { jq -r '.[].status | "  \(.name): \(.connector.state)"' 2>/dev/null || cat; }
fi

# =============================================================================
# 4. Compilar
# =============================================================================
if (( START_APPS )) && (( ! SKIP_BUILD )); then
    step "Compilando el reactor (mvn clean install -DskipTests)"
    mvn -q clean install -DskipTests
    ok "reactor compilado"
fi

# =============================================================================
# 5. Apps
# =============================================================================
start_app() {
    local module="$1" port="$2"
    local log="$LOG_DIR/${module}-app.log"
    local pidfile="$RUN_DIR/${module}.pid"

    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        ok "${module}-app ya está corriendo (pid $(cat "$pidfile"))"
        return 0
    fi
    # Sin -am a propósito: con él, spring-boot:run se ejecutaría también sobre el
    # POM padre, que no tiene main class, y el build falla. Las dependencias ya
    # están en el repo local por el 'mvn install' previo (o por --skip-build).
    nohup mvn -pl "$module" spring-boot:run > "$log" 2>&1 &
    echo $! > "$pidfile"
    ok "${module}-app lanzada (pid $(cat "$pidfile"), log: logs/${module}-app.log)"
    wait_http "http://localhost:${port}/actuator/health" 300 "${module}-app"
}

if (( START_APPS )); then
    step "Arrancando las aplicaciones"
    export SAP_BTP_BASE_URL="${SAP_BTP_BASE_URL:-http://localhost:${MOCK_SAP_PORT:-8090}}"
    export SAP_S4_BASE_URL="${SAP_S4_BASE_URL:-http://localhost:${MOCK_SAP_PORT:-8090}}"
    start_app customer "${CUSTOMER_SERVER_PORT:-8081}"
    start_app article  "${ARTICLE_SERVER_PORT:-8082}"
fi

# =============================================================================
# 6. Inventario de lo levantado
# =============================================================================

# jdbc_parts <url> — descompone una URL JDBC en los campos que pide DBeaver.
jdbc_parts() {
    local url="$1"
    if [[ "$url" =~ ^jdbc:sqlserver://([^:\;/]+):?([0-9]*).*databaseName=([^\;]+) ]]; then
        printf 'host %s · puerto %s · BD %s'             "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]:-1433}" "${BASH_REMATCH[3]}"
    elif [[ "$url" =~ ^jdbc:(postgresql|mysql)://([^:/]+):?([0-9]*)/([^?]+) ]]; then
        local defport=5432; [[ "${BASH_REMATCH[1]}" == "mysql" ]] && defport=3306
        printf 'host %s · puerto %s · BD %s'             "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]:-$defport}" "${BASH_REMATCH[4]}"
    elif false; then
        printf 'host %s · puerto %s · BD %s'             "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]:-5432}" "${BASH_REMATCH[3]}"
    else
        printf '%s' "$url"
    fi
}

group() { printf '
  [1m%s[0m
' "$*"; }
row()   { printf '    %-22s %s
' "$1" "$2"; }
sub()   { printf '    %-22s [2m%s[0m
' "" "$1"; }

step "Todo levantado — dónde está cada cosa"

if (( START_APPS )); then
    group "APLICACIONES"
    row "customer-app"     "http://localhost:${D_CUSTOMER_PORT}"
    sub "health http://localhost:${D_CUSTOMER_PORT}/actuator/health · métricas /actuator/prometheus"
    sub "POST /customers/sync · /customers/validate · GET /customers/{id}/history[/diff]"
    row "article-app"      "http://localhost:${D_ARTICLE_PORT}"
    sub "health http://localhost:${D_ARTICLE_PORT}/actuator/health"
    sub "POST /articles/sync · GET /articles/{id}/history[/diff]"
fi

group "SAP"
if [[ "$ENV_NAME" == "local" ]]; then
    row "SAP simulado"     "${D_MOCK_SAP}/__admin"
    sub "peticiones recibidas: ${D_MOCK_SAP}/__admin/requests"
    sub "stub catch-all activo: responde 201 a cualquier ruta"
else
    row "SAP BTP"          "${SAP_BTP_BASE_URL:-(sin configurar)}"
    row "SAP S/4"          "${SAP_S4_BASE_URL:-(sin configurar)}"
fi

group "BASES DE DATOS (datos de conexión para DBeaver)"
row "SQL Server (customer)" "$(jdbc_parts "$D_SQLSERVER")"
sub "$D_SQLSERVER"
if [[ "$ENV_NAME" == "local" ]]; then
    sub "usuario sa · clave SqlServer_Pa55w0rd! · marcar «Trust server certificate»"
    sub "tablas: dbo.customers · dbo.outbox_customer"
else
    sub "usuario ${SQLSERVER_USER:-(sin configurar)} · clave en scripts/env/test.env"
fi
row "PostgreSQL (article)" "$(jdbc_parts "$D_POSTGRES")"
sub "$D_POSTGRES"
if [[ "$ENV_NAME" == "local" ]]; then
    sub "usuario postgres · clave postgres"
    sub "tablas: articles · outbox_article"
else
    sub "usuario ${POSTGRES_USER:-(sin configurar)} · clave en scripts/env/test.env"
fi
row "MySQL (registro SDD)" "$(jdbc_parts "$D_MYSQL_SDD")"
sub "$D_MYSQL_SDD · usuario sdd / clave sdd · tablas feature y feature_evento"
sub "no es una BD de la app: es el registro de features solicitadas"
row "MongoDB (customer)" "$D_MONGO_CUSTOMER"
row "MongoDB (article)" "$D_MONGO_ARTICLE"
sub "colecciones *_current (imagen actual) y sync_state (transiciones de estado)"

group "MENSAJERÍA"
row "Kafka (broker)" "$D_KAFKA"
sub "topics outbox.CUSTOMER · outbox.ARTICLE · sus DLT <topic>.DLT"
row "Kafka Connect" "${D_CONNECT}/connectors"
if (( WITH_CDC )); then
    sub "conectores Debezium registrados: ${D_CONNECT}/connectors?expand=status"
else
    sub "sin conectores CDC (relanza con --with-cdc para registrarlos)"
fi

group "ELK"
row "Elasticsearch" "$D_ES"
sub "índices customers_history · articles_history (se crean en la 1ª escritura)"
if [[ "$ENV_NAME" == "local" ]]; then
    row "Kibana" "$D_KIBANA"
    sub "Dev Tools → GET customers_history/_search"
fi

if [[ "$ENV_NAME" == "local" ]]; then
    group "ALMACENAMIENTO"
    row "MinIO (consola)" "$D_MINIO_CONSOLE"
    sub "minioadmin / minioadmin123 · API S3 en $D_MINIO_API · bucket sap-integration (sin uso aún)"
fi

group "SIGUIENTE PASO"
if (( START_APPS )); then
    sub "smoke test (una línea, lista para copiar):"
    printf "      curl -s -X POST http://localhost:%s/customers/sync -H 'Content-Type: application/json' -d '{\"entityId\":\"CUST-001\",\"operation\":\"UPDATE\",\"payloadHash\":\"smoke-1\",\"payload\":\"{}\"}'
" "$D_CUSTOMER_PORT"
    sub "logs de las apps: tail -f logs/customer-app.log"
fi
sub "colección Postman: scripts/postman/poc-sap-integration.postman_collection.json"
sub "parar todo: ./scripts/stop-all.sh"
printf '
'
