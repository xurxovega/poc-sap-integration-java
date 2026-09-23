# Autenticación hacia SAP

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la usa `RestClientSapClient` para todos los dominios) |
| **Estado** | ✅ implementado |
| **Entradas** | configuración `sap.btp.xsuaa.*`, `sap.s4.auth.*`, `sap.auth.allow-stub` |
| **Destino SAP** | BTP (xsuaa) y S/4 nativo (OAuth2 del communication arrangement o basic del communication user) |
| **Última revisión** | 2026-09-12 |

## 1. Objetivo

Que cada llamada a SAP lleve una credencial **real** del destino al que va, y
que una instalación sin credenciales **no arranque** en vez de arrancar con un
token falso y fallar con `401` en la primera llamada, que es lo que pasaba hasta
la Fase 4 del plan de auditoría (A8). El token stub sigue existiendo para el SAP
simulado, pero hay que **pedirlo** de forma explícita.

## 2. Alcance

**Dentro**: obtención y caché del token OAuth2 (client-credentials) para BTP y
S/4, cabecera `Authorization` basic para S/4, validación al arranque, modo stub
explícito.

**Fuera** (y por qué):
- Cómo se usa la cabecera (fetch CSRF, cabeceras comunes): [`resiliencia-cliente-sap.md`](resiliencia-cliente-sap.md).
- Autenticación de **nuestras** APIs REST y actuator (auditoría B4): Fase 4 del
  plan, spec propio cuando exista.
- Gestión y rotación de secretos (Vault): backlog SEC-2.

## 3. Entrada

| Propiedad | Variable | Obligatoria | Notas |
|---|---|---|---|
| `sap.auth.allow-stub` | `SAP_AUTH_ALLOW_STUB` | no (por defecto `false`) | `true` solo contra el SAP simulado |
| `sap.btp.xsuaa.client-id` / `client-secret` / `token-url` | `SAP_BTP_CLIENT_ID` / `SAP_BTP_CLIENT_SECRET` / `SAP_BTP_TOKEN_URL` | las tres, salvo allow-stub | xsuaa client-credentials |
| `sap.s4.auth.type` | `SAP_S4_AUTH_TYPE` | no (por defecto `oauth2`) | `oauth2` \| `basic` |
| `sap.s4.auth.token-url` / `client-id` / `client-secret` | `SAP_S4_TOKEN_URL` / `SAP_S4_CLIENT_ID` / `SAP_S4_CLIENT_SECRET` | las tres con `oauth2`, salvo allow-stub | communication arrangement |
| `sap.s4.auth.username` / `password` | `SAP_S4_USERNAME` / `SAP_S4_PASSWORD` | ambas con `basic`, salvo allow-stub | communication user |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | Al arrancar, cada destino comprueba que su configuración está **completa**. Si falta algo y `sap.auth.allow-stub=false`, la app **no arranca**: `IllegalStateException` que nombra las propiedades que faltan y cómo autorizar el stub | Antes: arranque limpio y `401` en la primera llamada, imposible de distinguir de un problema de SAP |
| R-2 | Con `sap.auth.allow-stub=true` y configuración incompleta, el destino usa un token stub (`stub-btp-token` / `stub-s4-token`) y lo **avisa en el log al arrancar** | — |
| R-3 | Con configuración completa, `allow-stub` es irrelevante: siempre credencial real. El token OAuth2 se cachea y se renueva antes de caducar (`OAuth2TokenClient`) | — |
| R-4 | Con `sap.s4.auth.type=basic`, la cabecera es `Basic base64(usuario:clave)`; el usuario y la clave son los del communication user | — |
| R-5 | Ningún secreto tiene valor por defecto en los YAML empaquetados: ni credenciales SAP ni usuario/clave de las bases de datos legacy (`SQLSERVER_USER/PASSWORD`, `POSTGRES_USER/PASSWORD`). Sin ellos la app **no arranca**: `LegacyCredentialsGuard` falla al arrancar con las variables que faltan (Spring Boot deja el placeholder `${POSTGRES_USER}` literal y, sin el guard, el síntoma era un fallo de autenticación en la base de datos varias capas más abajo). `trustServerCertificate=true` tampoco es default: lo pone `scripts/env/local.env` para el contenedor de desarrollo | Auditoría A8: `sa`/`SqlServer_Pa55w0rd!` y `trustServerCertificate=true` iban dentro del jar |

## 5. Salida

Cabecera `Authorization` por destino: `Bearer <token>` (OAuth2 o stub) o
`Basic <base64>`. La consume `RestClientSapClient` en toda petición y en el
fetch CSRF.

## 6. Estados y errores

No toca la máquina de estados. Un fallo de configuración es un fallo de
**arranque**, no un `SAP_ERROR`.

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dada configuración incompleta y `allow-stub=false`, cuando arranca el provider, entonces falla con un mensaje que nombra las propiedades y la variable `SAP_AUTH_ALLOW_STUB` | `BtpAuthProviderTest#missingCredentialsWithoutAllowStubFailAtStartup` · `#missingTokenUrlCountsAsUnconfigured` · `S4NativeAuthProviderTest#oauth2WithoutConfigAndWithoutAllowStubFailsAtStartup` · `#basicAuthWithoutUserFailsAtStartupUnlessStubAllowed` |
| AC-2 | Dada configuración incompleta y `allow-stub=true`, entonces arranca y devuelve el token stub | `BtpAuthProviderTest#allowStubReturnsStubTokenAndPassesStartup` · `S4NativeAuthProviderTest#oauth2WithoutConfigReturnsStubTokenWhenAllowed` |
| AC-3 | Dada configuración completa, entonces arranca con `allow-stub=false` | `BtpAuthProviderTest#completeCredentialsPassStartupWithoutStub` · `S4NativeAuthProviderTest#completeOauth2CredentialsPassStartupWithoutStub` |
| AC-4 | Con `basic`, la cabecera es `Basic base64(usuario:clave)` | `S4NativeAuthProviderTest#basicAuthBuildsBasicHeader` |
| AC-5 | El contexto completo de cada app arranca **solo** si recibe credenciales de BD y `allow-stub` (o credenciales SAP) desde fuera del jar | `CustomerApplicationContextTest` · `ArticleApplicationContextTest` (fijan `SQLSERVER_*`/`POSTGRES_*` y `sap.auth.allow-stub=true` en sus propiedades) |
| AC-6 | Dado un placeholder de credencial de BD sin resolver o vacío, cuando arranca la app, entonces falla con un mensaje que nombra las variables y cómo cargarlas | `LegacyCredentialsGuardTest` (3 tests) |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

`WARN` al arrancar por cada destino en modo stub. El fallo de arranque llega
como excepción de Spring con el mensaje de R-1.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, R-2, R-3 (BTP) | `common/sap/auth/BtpAuthProvider.java` (`validate`, `accessToken`) | `BtpAuthProviderTest` |
| R-1, R-2, R-3, R-4 (S/4) | `common/sap/auth/S4NativeAuthProvider.java` | `S4NativeAuthProviderTest` |
| R-3 caché | `common/sap/auth/OAuth2TokenClient.java` | integración (pendiente contra tenant) |
| R-5 | `customer/application.yml`, `article/application.yml`, `application-common.yml` (`sap.auth.allow-stub`), `common/config/LegacyCredentialsGuard.java`, `scripts/env/local.env`, `scripts/env/test.env.example` | `*ApplicationContextTest` · `LegacyCredentialsGuardTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-12 | AC-6: `LegacyCredentialsGuard`. Al arrancar article-app sin `local.env` el placeholder `${POSTGRES_USER}` llegó literal a PostgreSQL (Boot no falla por placeholders sin resolver); ahora la app se niega a arrancar y dice qué exportar | — |
| 2026-09-12 | Spec inicial (plan: adelanto de la Fase 4 antes del tenant de test; auditoría A8). Fallback a token stub solo con `sap.auth.allow-stub=true` y aviso en log; sin credenciales la app no arranca. Usuario y clave de las BD legacy y `trustServerCertificate` fuera de los YAML empaquetados: los aporta el entorno (`scripts/env/*.env`) | — |
