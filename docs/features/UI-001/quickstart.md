# Quickstart — UI-001 Dashboard web: vista por entidad y búsqueda

> Arranque aislado para entender y verificar UI-001 sin tener que leer
> todo el proyecto. El QUICK_START general está en
> [`docs/QUICK_START.md`](../../QUICK_START.md). El spec completo en
> [`docs/sdd/customer/consulta-entidad-ui.md`](../../sdd/customer/consulta-entidad-ui.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.
- Keycloak levantado si `APP_SECURITY_ENABLED=true` (perfil
  `--with-observability` del quickstart general). En local con
  `APP_SECURITY_ENABLED=false` arranca sin Keycloak.
- `external-services/` levantado (Mongo, Elasticsearch, opcionalmente Keycloak).
- `customer-app` opcional: si quieres ver datos reales en el dashboard,
  primero ejecuta un `POST /customers/sync` para sembrar Mongo y ES.
  Sin eso, el dashboard arranca vacío.

## 2. Lo que vas a ver

- Un nuevo puerto **8091** sirviendo una web estática del dominio
  `customer`. Lee directo de Mongo (`customers_current`,
  `sync_state`, `alerts`) y de Elasticsearch (`customers_history`);
  **no** consume las APIs REST del `customer-app` — el aislamiento entre
  bounded contexts lo vigila `DashboardIsolationTest`.
- Pestaña "Histórico" con versiones enviadas a SAP, con su ETag y su hash.
- Pestaña "Alerts" (F-9 promoted): lista de alertas abiertas y
  botón "Reconocer" si eres `sap-write`.
- Búsqueda por NIF/CIF (`/customers/search?q=...`) y por id de cliente.
- Gauges Prometheus propios: `business_kpi_mttr_seconds{window="7d"}` y
  `business_kpi_recovery_p95_seconds{cycle="last"}` tras el primer tick del
  job (5 min por defecto).

## 3. Pasos

### a) Compilar el módulo nuevo

```bash
mvn -pl dashboard-customer -am package -DskipTests
```

### b) Arrancar `external-services/` (y `customer-app` si quieres datos)

```bash
./scripts/start-all.sh --with-observability   # con Keycloak local
```

Deja el entorno sirviendo: Mongo en `localhost:27017/customer`,
Elasticsearch en `localhost:9200`, Grafana en `localhost:3000` y el
`customer-app` en el 8081.

### c) Arrancar el dashboard

```bash
mvn -pl dashboard-customer spring-boot:run
# http://localhost:8091
```

### d) URLs a probar

- `http://localhost:8091/` — home con buscador.
- `http://localhost:8091/customers/CUST-001` — vista detalle con tabs
  (entidad, histórico, estado, alertas).
- `http://localhost:8091/customers/search?q=A12345678` — busca por NIF en
  `customers_current`.
- `http://localhost:8091/customers/CUST-001/history` — histórico ES.
- `http://localhost:8091/customers/CUST-001/history/diff?from=<docId>&to=<docId>` —
  diff campo a campo.
- `http://localhost:8091/customers/CUST-001/state` — estado del agregado y
  de cada feature con `lastCycle`.
- `http://localhost:8091/customers/alerts/open` — lista de alertas pendientes
  (cualquier rol de lectura).
- `http://localhost:8091/actuator/prometheus` — métricas del dashboard.
- `http://localhost:8091/actuator/health` — health estándar.

### e) PII enmascarada para `sap-external-read`

Pedir un token con ese rol (ver [`docs/tools-integrations/KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md))
y cargar `/customers/CUST-001`. El IBAN del snapshot aparece como
`********************0123` y la cabecera muestra el badge `PII enmascarada`.
Con `sap-read` el IBAN viene completo.

### f) Reconocer una alerta (F-9 promoted, requiere `sap-write`)

```bash
curl -X POST \
     -H "Authorization: Bearer <token-con-sap-write>" \
     http://localhost:8091/customers/alerts/<alert-id>/ack
```

Persiste `ackedAt`/`ackedBy` (formato `<rol>:<subject>`) en Mongo
`alerts` con TTL 30 días. Con `sap-read` devuelve 403; con un id
inexistente devuelve 404.

### g) Métricas Prometheus del dashboard (F-12)

```bash
curl -s http://localhost:8091/actuator/prometheus | grep dashboard-customer
```

Salida esperada: series con tag `application="dashboard-customer"` y, si
ha pasado un tick del job de KPIs (5 min por defecto),
`business_kpi_mttr_seconds{window="7d", application="dashboard-customer"}`.

### h) Enlace a Grafana con filtro por entidad

La cabecera del dashboard enlaza al panel Grafana del pipeline con la
variable `entityId` rellena:

```
http://localhost:3000/d/customer-pipeline-v1?var-entityId=CUST-001
```

## 4. Cómo deshacer / parar

```bash
# Detener el dashboard (Ctrl+C en su terminal)
# O el script general:
./scripts/stop-all.sh
```

## 5. Si algo falla

- **401 del dashboard**: ¿`KEYCLOAK_ISSUER_URI` apunta al Keycloak local?
  ¿el cliente `dashboard-customer` existe en el realm? Si trabajas
  sin Keycloak en local, exporta `APP_SECURITY_ENABLED=false` antes de
  arrancar.
- **Vista vacía**: ¿hay histórico en ES para `CUST-001`? Provocar
  primero un `POST /customers/sync` contra el `customer-app` para
  sembrar el estado.
- **Búsqueda por NIF no devuelve**: el histórico ES lleva
  `fiscal.taxId`, pero la búsqueda del dashboard va contra
  `customers_current` (Mongo). Asegúrate de que el cliente tiene un
  `fiscal.taxId` no nulo en el documento `customers_current`.
- **Job de KPIs a 0**: el primer tick es 5 minutos después del arranque
  (`app.dashboard.kpi.refresh-seconds:300`). Si no aparece, mira los
  logs del job: `MongoSyncState` no tiene errores en ventana.
- **`DashboardIsolationTest` falla**: alguien metió un
  `import com.poc.sap.customer.application...` (o `bootstrap.*` /
  `domain.*`) desde el módulo `dashboard`. Esa clase no puede vivir
  ahí: el dashboard reimplementa lo que necesita en su propio paquete
  `com.poc.sap.dashboard.customer.*` y lee Mongo+ES con clientes
  nativos.
- **PII no se enmascara para `sap-external-read`**: el JWT no lleva el
  rol, o `AccessScope.canSeeSensitiveData(...)` no detecta el rol.
  Revisar `common.security.AccessScope` y `PiiMasker.mask(...)`.

## 6. Lo que esta feature NO hace

- **No** reenvía a SAP. Es solo lectura + ack de alertas.
- **No** ejecuta acciones destructivas (re-sync forzoso, reproceso DLT).
  Eso es UI-4 y depende de OPS-2.
- **No** expone el dashboard a internet sin pasar por el Ingress del
  cluster; la variable `host` del `openapi.yml` es orientativa.

## 7. Ver también

- [`docs/sdd/customer/consulta-entidad-ui.md`](../../sdd/customer/consulta-entidad-ui.md) — spec.
- [`docs/features/UI-001/feature-execution-graph.html`](feature-execution-graph.html) — grafo de la ejecución.
- [`docs/features/UI-001/README.md`](README.md) — alcance y criterios.
- [`docs/QUICK_START.md`](../../QUICK_START.md) — arranque general del proyecto.
