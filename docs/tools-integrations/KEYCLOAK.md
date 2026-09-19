# Keycloak — qué crear para las APIs de sap-integration

> La empresa ya tiene Keycloak. Esta guía dice qué necesita la aplicación de él
> y cómo probarlo. La lógica del lado de la app está en
> [`../sdd/common/seguridad-api.md`](../sdd/common/seguridad-api.md) y
> [ADR-0007](../architecture/adr/0007-keycloak-como-proveedor-de-identidad-de-las-apis.md).
> Por qué Keycloak y no otro proveedor de identidad, y qué falta para cerrar
> los huecos de auditoría (`aud`, fail-closed): [`SERVICIO-AUTENTICACION.md`](SERVICIO-AUTENTICACION.md)
> y [ADR-0012](../architecture/adr/0012-servicio-externo-de-autenticacion-idp.md) (estado: propuesta).

## 1. Cliente

Un cliente `sap-integration` en el realm corporativo, **bearer-only** o
confidencial (la app no redirige a login: solo valida tokens). El nombre se
configura con `KEYCLOAK_CLIENT_ID`; el realm con `KEYCLOAK_ISSUER_URI`
(`https://<keycloak>/realms/<realm>`).

## 2. Roles

Pueden ser roles de **realm** o roles del **cliente** `sap-integration`: la app
lee los dos (`realm_access.roles` y `resource_access.sap-integration.roles`).

| Rol en Keycloak | Quién | Qué puede |
|---|---|---|
| `sap-read` | usuarios internos, servicios de lectura | leer histórico y diff completos (con PII) |
| `sap-write` | servicios de escritura, usuarios que operan | además, disparar `sync`/`validate` (escrituras facturables en S/4) |
| `sap-admin` | operación | además, actuator completo |
| `sap-superadmin` | administración | todo |
| `sap-external-read` | clientes externos | leer histórico con la PII **enmascarada**; sin diff ni escritura |

La jerarquía (superadmin ⊃ admin ⊃ write ⊃ read) la aplica la app: basta
asignar el rol más alto. Si se prefiere modelarla en Keycloak con roles
compuestos, no molesta.

## 3. Usuarios de servicio

Un cliente confidencial por servicio (client credentials) con el rol que
necesite (`sap-read` o `sap-write`), en vez de usuarios con contraseña.
Keycloak emite tokens con `client_credentials` y la app los valida igual.

## 4. Probar

```bash
# token de un usuario (password grant, solo para pruebas)
TOKEN=$(curl -s -X POST "$KEYCLOAK_ISSUER_URI/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=sap-integration -d client_secret=$SECRET \
  -d username=usuario -d password=clave | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/customers/CUST-001/history?full=true" | jq .masked
# read  -> false (snapshot completo)   external-read -> true (PII enmascarada)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/customers/CUST-001/history   # sin token -> 401

# el mismo token sirve para el resto de endpoints de customer, incl. el estado
# con la traza del último ciclo:
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/customers/CUST-001/state | jq .lastCycle
```

Contrato completo de los endpoints (parámetros, respuestas, seguridad) en
[`../../customer/src/main/resources/openapi.yml`](../../customer/src/main/resources/openapi.yml)
y su homólogo de `article`: se puede importar en Postman/Insomnia junto con el
`$TOKEN` de arriba.

En local con el SAP simulado no hay Keycloak: `scripts/env/local.env` pone
`APP_SECURITY_ENABLED=false` y la app avisa al arrancar de que va abierta.

## 5. Añadir un endpoint

Declara el acceso en el propio método, como un atributo de .NET:

```java
@PostMapping("/algo")
@PreAuthorize("hasRole('" + ApiRoles.WRITE + "')")
```

Sin `@PreAuthorize` el build falla (`EndpointsDeclareAccessTest`). Si el
endpoint devuelve datos personales y `sap-external-read` puede llamarlo,
enmascara antes de responder con `AccessScope.canSeeSensitiveData()` y un
*masker* como `PiiMasker`.
