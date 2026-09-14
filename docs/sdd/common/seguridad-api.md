# Seguridad de las APIs REST y de actuator

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la aplican los controllers de `customer` y `article`) |
| **Estado** | ✅ implementado (pendiente de probar contra el Keycloak corporativo) |
| **Entradas** | toda petición HTTP a `/customers/**`, `/articles/**` y `/actuator/**` |
| **Destino SAP** | ninguno |
| **Última revisión** | 2026-09-12 |

## 1. Objetivo

Que nadie sin identidad ni permiso pueda **disparar escrituras facturables en
S/4** (`/sync`) ni **leer datos personales** (IBAN, NIF, email, teléfono) del
histórico, y que los clientes externos puedan consultar sin ver esos datos.
Hasta la Fase 4 del plan de auditoría las APIs estaban abiertas (B4).

## 2. Alcance

**Dentro**: autenticación por JWT de Keycloak, roles y jerarquía, declaración
del acceso en cada endpoint, enmascarado de PII para lectura externa,
protección de actuator, modo abierto para el entorno local.

**Fuera** (y por qué):
- Alta de usuarios, clientes y roles en Keycloak: lo hace la empresa
  ([`../../tools-integrations/KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md) dice qué crear).
- TLS: lo termina el ingress del clúster ([`../../../deploy/README.md`](../../../deploy/README.md)).
- Enmascarado en **logs** y registro de accesos: backlog SEC-3.
- Autenticación hacia SAP: [`autenticacion-sap.md`](autenticacion-sap.md).

## 3. Entrada

| Propiedad | Variable | Default | Uso |
|---|---|---|---|
| `app.security.enabled` | `APP_SECURITY_ENABLED` | `true` | `false` solo en local con el SAP simulado: todo abierto y `WARN` |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `KEYCLOAK_ISSUER_URI` | *(vacío)* | `https://<keycloak>/realms/<realm>`; obligatorio con la seguridad activa |
| `app.security.keycloak.client-id` | `KEYCLOAK_CLIENT_ID` | `sap-integration` | cliente cuyos roles (`resource_access.<client>.roles`) se suman a los del realm |

El token viaja en `Authorization: Bearer <jwt>`; lo firma Keycloak y la app lo
valida contra las claves públicas del issuer (sin llamada por petición).

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Toda petición a la API lleva un JWT válido del issuer configurado; sin él, `401`. Sin `issuer-uri` y con la seguridad activa, la app **no arranca** | Antes: API abierta (B4) |
| R-2 | Los roles son los de Keycloak (realm o cliente) y se traducen a `ROLE_<MAYÚSCULAS>`: `sap-read`, `sap-write`, `sap-admin`, `sap-superadmin`, `sap-external-read`. Jerarquía: superadmin ⊃ admin ⊃ write ⊃ read. `external-read` no implica nada más | — |
| R-3 | **Cada endpoint declara quién puede llamarlo** con `@PreAuthorize` (el equivalente Java de los atributos de .NET). Un endpoint sin declaración rompe el build (`EndpointsDeclareAccessTest`, ArchUnit) | Un endpoint nuevo quedaría abierto sin que nadie lo notara |
| R-4 | A quien solo tiene `external-read` se le devuelve la PII **enmascarada** antes de responder (IBAN y NIF/IVA con los últimos 4, email con inicial y dominio, teléfono/fax con los últimos 3) y se le niega el `diff` (valores campo a campo). Los productos no tienen PII: los lee completos | — |
| R-5 | `health`, `info` y `prometheus` de actuator van sin token (sondas de Kubernetes y *scraping*); el resto de actuator exige `admin`. El ingress no expone `/actuator` | — |
| R-6 | Con `app.security.enabled=false` todo queda abierto y se avisa al arrancar: el usuario anónimo recibe `superadmin` y `external-read` para que las declaraciones `@PreAuthorize` no bloqueen nada. Nunca en test ni producción (el ConfigMap de despliegue lo fija a `true`) | Visto en vivo el 2026-09-14: la cadena permitía todo pero los endpoints devolvían 403 |

## 5. Salida

Matriz de acceso por endpoint:

| Endpoint | `external-read` | `read` | `write` | `admin` / `superadmin` |
|---|---|---|---|---|
| `POST /customers/sync`, `/customers/validate`, `POST /articles/sync` | ✗ 403 | ✗ 403 | ✓ | ✓ |
| `GET /customers/{id}/history` (`full=false`), `GET /customers/{id}/state` | ✓ | ✓ | ✓ | ✓ |
| `GET /customers/{id}/history?full=true` | ✓ **enmascarado** (`masked: true`) | ✓ completo | ✓ | ✓ |
| `GET /customers/{id}/history/diff` | ✗ 403 | ✓ | ✓ | ✓ |
| `GET /articles/{id}/history[/diff]` | ✓ | ✓ | ✓ | ✓ |
| `/actuator/health`, `/info`, `/prometheus` | sin token | | | |
| resto de `/actuator/**` | ✗ | ✗ | ✗ | ✓ |

## 6. Estados y errores

No toca la máquina de estados. `401` sin token o token inválido; `403` con
token pero sin rol; fallo de arranque sin issuer.

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Los roles de realm y del cliente configurado se convierten a `ROLE_*`; los de otros clientes se ignoran; sin roles, sin authorities | `KeycloakRoleConverterTest#realmAndClientRolesBecomeUpperCaseAuthorities` · `#tokenWithoutRolesYieldsNoAuthoritiesAndNamesMatchKeycloak` |
| AC-2 | La jerarquía hace que superadmin alcance admin, write y read, pero no external-read; `AccessScope` distingue lectura completa de externa y, sin autenticación, permite todo | `KeycloakRoleConverterTest#hierarchyImpliesLowerRolesButNotExternal` |
| AC-3 | Sin token: `401` en la API; health responde sin token | `ApiSecurityTest#anonymousIsRejectedExceptHealth` (customer) · `ApiSecurityTest#anonymousIsRejectedWriteNeedsWriteAndExternalCanReadProducts` (article) |
| AC-4 | `read` ve el snapshot completo; `external-read` lo recibe enmascarado (`masked: true`, sin IBAN) y `403` en el diff; el enmascarado conserva id, nombre y dirección | `ApiSecurityTest#externalReadGetsMaskedSnapshotAndNoDiff` · `PiiMaskerTest` |
| AC-5 | Escribir exige `write`: `read` recibe `403`, `write` `200` | `ApiSecurityTest#writeRequiresWriteRole` · article `ApiSecurityTest` |
| AC-6 | `admin` escribe, lee y ve `/actuator/metrics`; `write` no ve actuator | `ApiSecurityTest#adminInheritsWriteAndReadAndSeesActuator` |
| AC-7 | Todo método con `@GetMapping`/`@PostMapping`/... lleva `@PreAuthorize` | `EndpointsDeclareAccessTest` (ArchUnit, customer y article) |
| AC-11 | Con `app.security.enabled=false`, sin token: `sync` 200, histórico completo (`masked: false`), diff y actuator accesibles | `ApiSecurityDisabledTest#everythingIsOpenWithoutTokenWhenSecurityIsDisabled` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

`INFO` al arrancar con issuer y client-id; `WARN` si la seguridad está
desactivada. Los `401`/`403` los cuentan las métricas HTTP de Boot
(`http_server_requests_seconds_count{status="403"}`).

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, R-5, R-6 | `common/security/ApiSecurityConfig.java` | `ApiSecurityTest` (customer, article) |
| R-2 | `common/security/ApiRoles.java` · `KeycloakRoleConverter.java` | `KeycloakRoleConverterTest` |
| R-3 | `@PreAuthorize` en `customer/bootstrap/web/*Controller` y `article/bootstrap/web/*Controller` | `EndpointsDeclareAccessTest` ×2 |
| R-4 | `common/security/AccessScope.java` · `customer/bootstrap/web/PiiMasker.java` · `CustomerHistoryController` | `PiiMaskerTest` · `ApiSecurityTest#externalReadGetsMaskedSnapshotAndNoDiff` |
| Configuración | `application-common.yml` (`app.security.*`, `issuer-uri`), `customer/application.yml` (`show-details: when-authorized`), `scripts/env/*.env`, `deploy/k8s/base/common.yaml` | `*ApplicationContextTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-14 | R-6/AC-11: en modo abierto el anónimo lleva todos los roles; antes los `@PreAuthorize` devolvían 403 en local (visto en vivo) | — |
| 2026-09-12 | Spec inicial (plan Fase 4, auditoría B4; decisión del usuario: Keycloak, roles por endpoint y filtrado de PII para lectura externa). Resource server JWT, cinco roles con jerarquía, `@PreAuthorize` obligatorio por ArchUnit, `PiiMasker`, actuator protegido, modo abierto solo en local | — |
