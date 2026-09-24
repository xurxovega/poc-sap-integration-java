# Quickstart — PRD-9 Consulta de Business Partner en SAP (GET, sin coste)

> Arranque aislado para entender y verificar PRD-9 sin tener que leer
> todo el proyecto. El QUICK_START general está en
> [`docs/QUICK_START.md`](../../QUICK_START.md). El spec completo en
> [`docs/sdd/customer/consulta-business-partner-sap.md`](../../sdd/customer/consulta-business-partner-sap.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`).
- `customer-app` levantado, con `sap.odata.read.enabled=true`
  **o** `sap.odata.customer.enabled=true` en el `application.yml` o
  en `scripts/env/local.env` (sin esto, los endpoints no se
  registran y `GET` devuelve 404 desde el dispatcher — ver R-5 del
  spec).
- `customer-app` requiere Mongo, SQL Server, Kafka (CDC) y Keycloak
  para arrancar el contexto completo; el smoke
  `CustomerApplicationContextTest` valida que el bean y el controller
  se arman/desarman según la propiedad.
- **No** hace falta SAP: los unit y slice usan el puerto mockeado;
  para una prueba real contra el tenant, ver §5.

## 2. Lo que vas a ver

Cuatro endpoints REST GET nuevos en `customer-app` (puerto 8081):

- `GET /business-partners/{code}` — un BP por clave.
- `GET /business-partners?category=<n>&top=<n>` — búsqueda por
  categoría OData con `$top`.
- `GET /business-partners/customers?top=<n>` — atajo clientes.
- `GET /business-partners/suppliers?top=<n>` — atajo proveedores.

La forma de respuesta es siempre la misma:

- Lookup por id: `{"code": "...", "name": "...", "category": "...", "masked": false}`
- Búsqueda: `{"results": [{...}, {...}], "masked": false}`

Si el llamador solo tiene `sap-external-read`, el `name` viene
enmascarado (asteriscos + últimos 4 caracteres) y `masked: true`.
`code` y `category` no se enmascaran (no son PII).

## 3. Pasos

### a) Compilar el módulo

```bash
mvn -pl customer -am package -DskipTests
```

### b) Arrancar `external-services/` y `customer-app`

```bash
./scripts/start-all.sh   # Mongo, SQL Server, Kafka, Keycloak + apps
```

El `customer-app` arranca con `sap.odata.read.enabled` en su valor
por defecto (`false`). Para activar la feature PRD-9 hay que
sobreescribirlo (en `scripts/env/local.env` o en el arranque):

```bash
SPRING_APPLICATION_JSON='{"sap.odata.read.enabled":true}'
mvn -pl customer spring-boot:run
```

### c) Pedir un token a Keycloak

Para probar el enmascarado del `sap-external-read` necesitas un
token con ese rol. Pasos en
[`docs/tools-integrations/KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md).

### d) URLs a probar

Con `sap-read`:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/realms/sap-integration/protocol/openid-connect/token \
  -d grant_type=password -d client_id=sap-integration \
  -d username=operator -d password=operator | jq -r .access_token)

# Lookup por id
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/business-partners/C001 | jq .

# Búsqueda por categoria (clientes, top=10)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/business-partners?category=2&top=10" | jq .

# Atajos
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/business-partners/customers?top=5" | jq .
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/business-partners/suppliers?top=5" | jq .
```

Con `sap-external-read` (mismo endpoint, distinto token):

```bash
TOKEN_EXT=$(... token con rol sap-external-read ...)

# El `name` viene enmascarado y masked=true
curl -s -H "Authorization: Bearer $TOKEN_EXT" \
  http://localhost:8081/business-partners/C001 | jq .
# -> { "code": "C001", "name": "****************S.L.", "category": "2", "masked": true }
```

Casos de error:

```bash
# 404 cuando SAP no tiene ese BP
curl -s -o - -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/business-partners/C-QUE-NO-EXISTE
# -> 404, body {"error":"BP C-QUE-NO-EXISTE no existe en SAP"}

# 400 cuando top fuera de [1, 200]
curl -s -o - -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/business-partners?category=2&top=999"
# -> 400, body {"error":"top fuera de rango [1..200]: [999]"}

# 403 sin token o con rol insuficiente
curl -s -o - -w "%{http_code}\n" \
  http://localhost:8081/business-partners/C001
# -> 401 sin token, 403 con rol no admitido
```

## 4. Parar

```bash
./scripts/stop-all.sh
```

## 5. Si falla

- **404 en todos los endpoints**: `sap.odata.read.enabled` o
  `sap.odata.customer.enabled` no están a `true`; o el
  `BusinessPartnerReadAdapter` no se está montando (mira los logs
  del arranque, debe aparecer el bean
  `businessPartnerReadAdapter`). Sin esto, el controller es
  condicional y no se registra (R-5).
- **500 con timeout**: `BusinessPartnerReadAdapter` está hablando
  con un SAP que no responde (o el WireMock no está donde debería).
  Verifica la propiedad `sap.odata.bp-path` y la URL del destino
  S/4.
- **`masked: true` aunque seas `sap-read`**: el `AccessScope` no ve
  el rol `SAP_READ` por jerarquía; revisa que `ApiRoles.hierarchy()`
  está siendo aplicado (`common.security.ApiSecurityConfig`).
- **Tests rojos en `LookupBusinessPartnerUseCaseTest`**: la
  validación de `top` cambió; si has tocado el rango en el spec,
  actualiza el test y el AC-5.
- **`OpenApiMatchesControllersTest` rojo**: el contrato
  (`customer/src/main/resources/openapi.yml`) y los
  `@PreAuthorize` de los endpoints no coinciden; el test dice
  exactamente qué rol declara cada uno. La regla es:
  `x-required-role` en YAML tiene que ser el mismo set que el
  `hasAnyRole(...)` del endpoint.

## 6. Verificación contra el tenant SAP de test

Pendiente (mismo criterio que `sincronizacion-contacto.md` y
`sincronizacion-datos-bancarios.md`). Pasos cuando se ejecute:

1. Levantar `customer-app` con un `application.yml` que apunte al
   tenant SAP de test (`S4_BASE_URL`, `S4_AUTH_URL`,
   `S4_AUTH_CLIENT_ID`, `S4_AUTH_CLIENT_SECRET`).
2. `SPRING_APPLICATION_JSON='{"sap.odata.read.enabled":true}' mvn -pl customer spring-boot:run`.
3. Con un `sap-read` real: probar los 4 endpoints contra códigos
   conocidos (cliente y proveedor existentes en el tenant).
4. Confirmar que la respuesta tiene `code`, `name`, `category`
   con los valores esperados.
5. Probar un código que no exista → 404 con
   `{"error":"BP <code> no existe en SAP"}`.
6. Comprobar el contrato OpenAPI con la colección Postman
   `scripts/postman/customer/`.
