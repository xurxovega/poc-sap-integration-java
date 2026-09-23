# Quickstart — UI-001 Dashboard web: vista por entidad y búsqueda

> Arranque aislado para entender y verificar UI-001 sin tener que leer
> todo el proyecto. El QUICK_START general está en
> [`docs/QUICK_START.md`](../../QUICK_START.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.
- Keycloak levantado si `APP_SECURITY_ENABLED=true` (perfil
  `--with-observability` del quickstart general). En local con
  `APP_SECURITY_ENABLED=false` arranca sin Keycloak.

## 2. Lo que vas a ver

- Un nuevo puerto **8091** sirviendo una web estática del dominio
  `customer`. Lee directo de Mongo (`customers_current`,
  `sync_state`, `alerts`) y de Elasticsearch (`customers_history`);
  **no** consume las APIs REST del `customer-app`.
- Pestaña "Histórico" con versiones enviadas a SAP.
- Pestaña "Alerts" (F-9 promoted): lista de alertas abiertas y
  botón "Reconocer" si eres `sap-write`.
- Búsqueda por NIF/CIF y por id de cliente.

## 3. Pasos

### a) Compilar el módulo nuevo

```bash
cd ../..                              # raíz del repo
./mvnw -pl dashboard-customer -am package -DskipTests
```

### b) Arrancar `external-services/` y `customer-app`

```bash
./scripts/start-all.sh --with-observability   # con Keycloak local
```

Deja el entorno sirviendo: Mongo en `localhost:27017/customer`,
Elasticsearch en `localhost:9200` y el `customer-app` en el 8081.

### c) Arrancar el dashboard

```bash
./mvnw -pl dashboard-customer spring-boot:run
# http://localhost:8091
```

### d) Verificar las rutas

- `GET /` redirige al buscador (en Thymeleaf no hay `/` declarado aún,
  arrancar por `/customers/search`).
- `GET /customers/CUST-001` → vista con cabecera + tabs + contenido.
- `GET /customers/search?q=A12345678` → busca por NIF en
  `customers_current`.
- `GET /customers/CUST-001/history` y `/history/diff` → histórico ES
  y diff campo a campo.
- `GET /customers/alerts/open` → lista de alertas pendientes de
  reconocer.
- `POST /customers/alerts/{id}/ack` (rol `sap-write`) → persiste
  `ackedAt`/`ackedBy`.

### e) PII enmascarada para `sap-external-read`

Pedir un token con ese rol y comprobar que el IBAN del snapshot viene
como `********************3000` y el badge `PII enmascarada` aparece
en la cabecera.

### f) Métricas Prometheus

```bash
curl -s http://localhost:8091/actuator/prometheus | grep dashboard
```

Salida esperada: series con tag `application="dashboard-customer"` y, si
ha pasado un tick del job de KPIs (5 min por defecto),
`business_kpi_mttr_seconds{window="7d", application="dashboard-customer"}`.

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
  `customers_current` (Mongo). Asegurate de que el cliente tiene un
  `fiscal.taxId` no nulo en el documento `customers_current`.
- **Job de KPIs a 0**: el primer tick es 5 min después del arranque
  (`app.dashboard.kpi.refresh-seconds:300`). Si no aparece, mira los
  logs del job: `MongoSyncState` no tiene errores en ventana.
- **`DashboardIsolationTest` falla**: alguien metio un `import
  com.poc.sap.customer.application...` desde el modulo dashboard. Esa
  clase no puede vivir ahi (lectura directa Mongo+ES).
