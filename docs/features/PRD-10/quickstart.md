# Quickstart — PRD-10 Upsert manual de Business Partner (PUT/PATCH, facturable)

> Arranque aislado para entender y verificar PRD-10 sin tener que leer
> todo el proyecto. El QUICK_START general está en
> [`docs/QUICK_START.md`](../../QUICK_START.md). El spec completo en
> [`docs/sdd/customer/upsert-business-partner-manual.md`](../../sdd/customer/upsert-business-partner-manual.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`).
- `customer-app` levantado con `sap.odata.customer.enabled=true` en el
  `application.yml` o en `scripts/env/local.env` (sin esto, los
  endpoints no se registran — R-6 del spec).
- `customer-app` requiere Mongo, SQL Server, Kafka (CDC) y Keycloak para
  arrancar el contexto completo.
- **No** hace falta SAP: los unit y slice usan el puerto mockeado; para
  una prueba real contra el tenant, ver §5.

## 2. Lo que vas a ver

Dos endpoints REST nuevos en `customer-app` (puerto 8081):

- `PUT /business-partners/{id}` — alta o actualización completa.
- `PATCH /business-partners/{id}` — actualización parcial (solo `name` en
  esta versión).

Ambos disparan el mismo pipeline que el CDC: el orquestador calcula el
hash sobre el `Customer` construido del body, aplica dedupe contra el
último `SENT_SAP`, y si hay cambios hace lookup + `PATCH` con `If-Match`
en SAP (o `POST` si el BP no existía). Cada llamada es una escritura
contra SAP y se factura — el rol mínimo es `sap-write`.

La respuesta siempre es la misma forma:

```json
{ "entityId": "C001", "state": "SENT_SAP" }
```

`state` es el estado final del ciclo (`SENT_SAP`, `SAP_ERROR`,
`INVALID`, `ERROR`, …).

## 3. Pasos

### a) Compilar el módulo

```bash
mvn -pl customer -am package -DskipTests
```

### b) Arrancar `external-services/` y `customer-app`

```bash
./scripts/start-all.sh   # Mongo, SQL Server, Kafka, Keycloak + apps
```

El `customer-app` arranca con `sap.odata.customer.enabled` en su valor
por defecto (`false`). Para activar la feature PRD-10 hay que
sobreescribirlo:

```bash
SPRING_APPLICATION_JSON='{"sap.odata.customer.enabled":true}'
mvn -pl customer spring-boot:run
```

### c) Pedir un token a Keycloak con rol `sap-write`

Pasos en [`docs/tools-integrations/KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md).

### d) URLs a probar

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/realms/sap-integration/protocol/openid-connect/token \
  -d grant_type=password -d client_id=sap-integration \
  -d username=operator -d password=operator | jq -r .access_token)

# PUT — alta (o update si ya existe)
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X PUT http://localhost:8081/business-partners/C001 \
  -d '{"name":"Foo SL","category":"2"}' | jq .
# -> { "entityId": "C001", "state": "SENT_SAP" }

# PUT repetido con el mismo body → dedupe → SENT_SAP sin reenviar
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X PUT http://localhost:8081/business-partners/C001 \
  -d '{"name":"Foo SL","category":"2"}' | jq .

# PATCH — solo cambia el nombre (category se rechaza en esta version, R-1)
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -X PATCH http://localhost:8081/business-partners/C001 \
  -d '{"name":"Foo SL renamed"}' | jq .
```

Casos de error:

```bash
# 400 — PUT sin name
curl -s -o - -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X PUT http://localhost:8081/business-partners/C001 \
  -d '{"category":"2"}'
# -> 400, body {"error":"name obligatorio"}

# 400 — PATCH sin campos
curl -s -o - -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X PATCH http://localhost:8081/business-partners/C001 \
  -d '{}'
# -> 400, body {"error":"al menos un campo: name, category"}

# 400 — PATCH con category (R-1 del spec)
curl -s -o - -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X PATCH http://localhost:8081/business-partners/C001 \
  -d '{"category":"2"}'
# -> 400, body {"error":"category no se puede modificar por PATCH en esta version"}

# 409 — Conflicto concurrente (otro ciclo sobre la misma entidad)
# -> application/problem+json con title "Sincronizacion concurrente"

# 403 — Sin rol WRITE
# -> 403 (AccessDenied)
```

## 4. Parar

```bash
./scripts/stop-all.sh
```

## 5. Si falla

- **404 en los endpoints**: `sap.odata.customer.enabled=false`. Hay que
  activarlo para que `BusinessPartnerODataAdapter` se monte y, con él,
  el bean y el controller de PRD-10 (ver R-6 del spec).
- **500 con timeout**: el `BusinessPartnerODataAdapter` está hablando
  con un SAP que no responde (o el WireMock no está donde debería).
  Verifica `sap.odata.customer-path` y la URL del destino S/4.
- **`state: SAP_ERROR` en la respuesta**: SAP rechazó la escritura.
  Mira el log del `customer-app` para ver el motivo concreto (SAP
  suele devolver un 4xx con detalle).
- **`state: INVALID`**: el orquestador valida el agregado; si el
  `name` está vacío, va a `INVALID`. Pasa por el `validate` del
  orquestador antes del envío.
- **Tests rojos en `UpsertBusinessPartnerUseCaseTest`**: cambió la
  validación del body (R-1 sobre `category` en PATCH). Actualiza el
  test y el spec.
- **`OpenApiMatchesControllersTest` rojo**: el contrato
  (`customer/src/main/resources/openapi.yml`) y los `@PreAuthorize`
  no coinciden. La regla: `x-required-role` en YAML tiene que ser
  `SAP_WRITE`, el mismo que el `hasRole(...)` del endpoint.
- **`TestCountMatchesDocsTest` rojo**: añadiste/quitaste `@Test` sin
  actualizar la cifra en `docs/testing/TESTING.md` §1 (y los demás
  sitios donde se cita; mira `TestCountMatchesDocsTest` para la lista
  completa).

## 6. Verificación contra el tenant SAP de test

Pendiente (mismo criterio que
`sincronizacion-contacto.md`, `sincronizacion-datos-bancarios.md` y
PRD-9). Pasos cuando se ejecute:

1. Levantar `customer-app` con un `application.yml` que apunte al
   tenant SAP de test (`S4_BASE_URL`, `S4_AUTH_URL`,
   `S4_AUTH_CLIENT_ID`, `S4_AUTH_CLIENT_SECRET`).
2. `SPRING_APPLICATION_JSON='{"sap.odata.customer.enabled":true}' mvn -pl customer spring-boot:run`.
3. Con un `sap-write` real:
   - `PUT /business-partners/C-NUEVO` con `{"name":"Cliente nuevo SL"}`
     → debe crear el BP en SAP y devolver `SENT_SAP`.
   - Repetir el mismo `PUT` → debe quedar en `SENT_SAP` sin reenviar
     (dedupe por hash).
   - `PUT /business-partners/C-NUEVO` con un nombre distinto → debe
     actualizar el BP en SAP con `PATCH` y devolver `SENT_SAP`.
   - `PATCH /business-partners/C-NUEVO` con `{"name":"Otro nombre"}`
     → debe actualizar y devolver `SENT_SAP`.
4. Comprobar el contrato OpenAPI con la colección Postman
   `scripts/postman/customer/`.
