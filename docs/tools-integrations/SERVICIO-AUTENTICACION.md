# Servicio externo de autenticación — Keycloak, y alternativas evaluadas

> Quién puede leer esto: cualquiera que necesite entender **qué** [IdP](../GLOSSARY.md#idp-identity-provider-proveedor-de-identidad) usa la aplicación, por qué, y qué cambiaría si se sustituyera. La decisión formal (estado, cuándo se reevalúa) vive en [ADR-0012](../architecture/adr/0012-servicio-externo-de-autenticacion-idp.md); este documento es la guía de referencia con el detalle técnico y el plan de adopción. La configuración operativa (roles, clientes, cómo probar) sigue en [`KEYCLOAK.md`](KEYCLOAK.md).

Adaptado de la propuesta de arquitectura del 2026-09-18 (`solution-architect`, revisión 3 del saneamiento de la integración SAP). Fuentes web citadas con su URL y fecha de consulta (2026-09-18); lo no verificado se marca explícitamente.

## 0. Recomendación en una frase

**Mantener Keycloak** (ya operado por la empresa) como [IdP](../GLOSSARY.md#idp-identity-provider-proveedor-de-identidad): es el único de los tres candidatos evaluados cuyo formato de roles en el token (`realm_access.roles` / `resource_access.<client-id>.roles`) coincide **hoy** con lo que la aplicación ya sabe leer (`KeycloakRoleConverter.java:33-38`). Cambiar de IdP no es gratis: cuesta un conversor de roles nuevo y reabrir [ADR-0007](../architecture/adr/0007-keycloak-como-proveedor-de-identidad-de-las-apis.md). Esta es una decisión que **corresponde al propietario del proyecto**; [ADR-0012](../architecture/adr/0012-servicio-externo-de-autenticacion-idp.md) queda en estado **Propuesta** hasta que la tome.

## 1. Requisitos derivados del repositorio

| # | Requisito | De dónde sale | Por qué es obligatorio |
|---|---|---|---|
| R-A | [OIDC](../GLOSSARY.md#oidc-openid-connect) con `issuer-uri` y **JWKS** público (conjunto de claves públicas con las que se verifica la firma del token sin llamar al IdP en cada petición) | `common/src/main/resources/application-common.yml:125` · `ApiSecurityConfig.java:48-54,65` | La verificación está en la ruta crítica de `/customers/sync`; una introspección por petición añadiría un salto de red por mensaje |
| R-B | Arranque *fail-fast*: sin issuer configurado, la app no arranca | `ApiSecurityConfig.java:50-54` | Ya implementado. El IdP debe existir **antes** del despliegue |
| R-C | [`client_credentials`](../GLOSSARY.md#client_credentials) para máquinas | `KEYCLOAK.md` §3 · ADR-0007 | Los llamadores reales son sistemas: CDC/legacy, SAP BTP (modo pull, propuesto), lector externo. Un IdP sin este *grant* no sirve |
| R-D | Roles en el token con el formato que la app ya lee: `realm_access.roles` y `resource_access.<client-id>.roles`, valores `sap-read`, `sap-write`, `sap-admin`, `sap-superadmin`, `sap-external-read` | `KeycloakRoleConverter.java:33-38,52` · `ApiRoles.java:15-23,26-28` | Con otro IdP hace falta reescribir el conversor: coste de migración que no aparece en ninguna comparativa comercial |
| R-E | Jerarquía superadmin ⊃ admin ⊃ write ⊃ read; `external-read` fuera de la cadena | `ApiRoles.java:30-36` | La aplica la app, no el IdP: cualquier IdP vale mientras emita los nombres |
| R-F | [`aud`](../GLOSSARY.md#aud-audiencia) (audiencia) distinta por sistema llamador | Hallazgo **2A-1**: `ApiSecurityConfig.java:48-65` valida solo `issuer-uri`; grep de `audience\|JwtValidators\|OAuth2TokenValidator` = 0 resultados | En un realm compartido, cualquier token del mismo emisor con un rol `sap-*` entra, se emitiera para la aplicación que se emitiera |
| R-G | Federación con el directorio corporativo (LDAP/Active Directory) para operadores humanos *(supuesto: la empresa tiene un directorio corporativo y no quiere una segunda base de usuarios — a confirmar)* | ADR-0007 | Sin esto se crea un silo de contraseñas nuevo |
| R-H | Alta disponibilidad en los dos clústeres (test y prod), con ≥2 réplicas | [ADR-0008](../architecture/adr/0008-kubernetes-como-plataforma-de-despliegue.md) | Si el IdP cae, la app responde 401 a todo: es una dependencia global |
| R-I | Rotación de secretos de cliente sin parada | `deploy/README.md` (gestión de secretos aún por decidir, OPS-7 en [`MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md)) | Un secreto que no se puede rotar es un secreto que no se rota nunca |
| R-J | Auditoría de accesos: quién pidió qué token y cuándo | `docs/sdd/common/seguridad-api.md` (registro de accesos fuera de alcance, SEC-3) | Sin esto, un acceso indebido a datos con IBAN/NIF es indetectable a posteriori |
| R-K | Retención y borrado de los propios registros del IdP (datos personales de empleados) | Mismo vacío que el resto de almacenes con PII (2A-4) | Repetir el hueco de retención en el IdP sería reincidencia |
| R-L | Configuración reproducible: realm, clientes y roles como código, no clics en una consola | ADR-0008 (todo declarativo con Kustomize) | Dos clústeres configurados a mano divergen en meses |
| R-M | `/actuator/health`, `/info` y `/prometheus` siguen sin token | `ApiSecurityConfig.java:62` | El IdP no puede estar en el camino de las sondas de Kubernetes |

**Lo que el IdP no cubre**: el CDC (legacy → Kafka → listener) no pasa por HTTP (`AGENTS.md` §2.3); ningún IdP lo protege. Eso es autenticación y TLS en el bróker (hallazgo 2A-3), un trabajo distinto de este.

## 2. Opción A — Keycloak (recomendada)

IdP de código abierto (Apache 2.0), proyecto *incubating* de la CNCF desde abril de 2023 ([cncf.io](https://www.cncf.io/blog/2023/04/11/keycloak-joins-cncf-as-an-incubating-project/), consultado 2026-09-18), con distribución comercial soportada de Red Hat (RHBK).

- **Despliegue**: Keycloak Operator, base de datos PostgreSQL externa (el Operator no la gestiona), topología *single-cluster* multi-AZ recomendada — una instalación por clúster (test y prod), no multi-cluster ([keycloak.org/high-availability](https://www.keycloak.org/high-availability/introduction), consultado 2026-09-18).
- **Realm dedicado** `sap-integration` (no el corporativo compartido): acota el radio de confianza mientras `aud` no se valide (motivo directo de 2A-1).
- **Un cliente por sistema llamador**, todos confidenciales, con su propio `aud`. Keycloak **no** incluye la audiencia automáticamente: hace falta un *Audience mapper* por cliente, y en la app, un validador de audiencia — ninguno de los dos existe hoy.
- **Roles**: se crean con los nombres que `ApiRoles.keycloakName()` ya espera; recomendado como roles del cliente configurado en `app.security.keycloak.client-id`, no del realm, para acotar aún más el radio de confianza.
- **Coste operativo** *(estimación, sin verificar contra un despliegue real)*: 3-5 jornadas de puesta en marcha, ≈0,5 jornada/mes de operación.

## 3. Opciones evaluadas y descartadas

| Candidata | Veredicto | Motivo |
|---|---|---|
| **Zitadel** | Alternativa fuerte | [OIDC](../GLOSSARY.md#oidc-openid-connect) completo, `aud` correcto **de serie** ([zitadel.com/docs/apis/openidoauth/claims](https://zitadel.com/docs/apis/openidoauth/claims), consultado 2026-09-18), multi-tenant real. Coste real: los roles llegan en el claim `urn:zitadel:iam:org:project:roles`, que **no** es lo que lee `KeycloakRoleConverter.java:33-38` — hace falta un conversor nuevo y reabrir ADR-0007. Licencia AGPLv3 (o acuerdo comercial), a revisar con legal |
| **authentik** | Alternativa práctica | La más fácil de administrar (MIT, consola accesible); LDAP/AD como fuente. Los claims son moldeables por *property mappings*: puede llegar a imitar el formato de Keycloak, pero es una convención frágil que habría que documentar explícitamente. Limitación fuerte: **un solo secreto por proveedor a la vez** ([docs.goauthentik.io](https://docs.goauthentik.io/add-secure-apps/providers/oauth2/client_credentials/), consultado 2026-09-18), lo que impide la rotación solapada (R-I) |
| Ory Hydra + Kratos | Descartada | Hydra no gestiona usuarios: delega el login en una aplicación externa que hay que escribir y mantener ([github.com/ory/hydra](https://github.com/ory/hydra), consultado 2026-09-18) |
| Dex | Descartada | Conector federador, no almacén de identidades; `client_credentials` no es su caso de uso ([dexidp/dex#2101](https://github.com/dexidp/dex/discussions/2101), consultado 2026-09-18) — choca con R-C, el requisito central |
| Casdoor | Descartada | Funcionalmente amplio pero con menos material de operación (HA, actualizaciones) que las otras tres *(valoración cualitativa, sin verificar con métricas de comunidad)* |

## 4. Qué cambia en la aplicación

Sin código (lo implementa `dev-implementer` con su spec y su test en rojo, según [`AGENTS.md`](../../AGENTS.md) §1.1/§1.2); esto es la lista de lo que falta, no un plan cerrado:

| # | Cambio | Fichero | Estado hoy |
|---|---|---|---|
| 1 | Validar `aud` | `common/.../security/ApiSecurityConfig.java:48-65` | No existe (grep `JwtValidators`: 0) |
| 2 | Configurar la audiencia esperada | `common/src/main/resources/application-common.yml` (junto a `issuer-uri:125`) | No existe |
| 3 | `AccessScope` [fail-closed](../GLOSSARY.md#fail-open--fail-closed) | `common/.../security/AccessScope.java:27-30` | **Fail-open**: sin autenticación devuelve `true` y sirve PII sin enmascarar (2A-2) |
| 4 | Guard de arranque que rechace `APP_SECURITY_ENABLED=false` fuera de local | junto a `ApiSecurityConfig.java:70-82` | No existe; hoy solo hay un `WARN` |
| 5 | Spec y criterios de aceptación nuevos | `docs/sdd/common/seguridad-api.md` | No existe |
| 6 | Guía de Keycloak con audience mapper por cliente | [`KEYCLOAK.md`](KEYCLOAK.md) | Hoy describe un único cliente |

**Ningún IdP cierra 2A-1 por sí solo**: la validación de `aud` es un cambio en la aplicación, con su spec y su test en rojo primero. El IdP solo la hace posible.

## 5. Plan de adopción (si se mantiene Keycloak)

| Paso | Qué | Criterio de aceptación |
|---|---|---|
| P0 | Realm `sap-integration` en el clúster de test, con los 5 roles | El `.well-known/openid-configuration` del issuer responde 200; el realm se aplica declarativamente (`KeycloakRealmImport`), no a mano |
| P1 | Probar con token real: 401 sin token, 403 con rol insuficiente, PII enmascarada con `sap-external-read` | Casos de [`KEYCLOAK.md`](KEYCLOAK.md) §4 en verde contra el Keycloak real |
| P2 | Un cliente por llamador, cada uno con su `aud` | Un token de un cliente no lleva la audiencia de otro |
| P3 | Validar `aud` en la app (2A-1) | Test `ApiSecurityTest#tokenForAnotherClientIsRejected` en verde |
| P4 | `AccessScope` fail-closed + guard de modo abierto (2A-2) | `AccessScopeTest#anonymousNeverSeesSensitiveData` en verde; arrancar con `APP_SECURITY_ENABLED=false` fuera de local falla explícitamente |
| P5 | Producción: HA, rotación de secretos, retención de los registros del IdP | Prueba de fallo (matar una réplica, la API sigue sirviendo mientras el JWKS esté cacheado) |

P3 y P4 son cambios de código con ancla SDD (`AGENTS.md` §1.1): llevan su `AC-n`, su test rojo previo y su línea de changelog. P4 no depende del IdP: puede hacerse ya.

## 6. Riesgos

| # | Riesgo | Mitigación |
|---|---|---|
| RG-1 | El IdP se convierte en dependencia global: si cae, todo responde 401 | HA multi-AZ desde el primer día; caché de JWKS (ya es el comportamiento por defecto del resource server) |
| RG-2 | Si el realm acaba siendo el corporativo compartido, `aud` pasa a ser el **único** control frente a 2A-1 | Validar `aud` es innegociable en ese escenario |
| RG-3 | Rotación de secretos sin mecanismo acordado (OPS-7 sigue abierto) | Decidir el mecanismo de secretos en Kubernetes antes de producción |
| RG-4 | RGPD en el propio IdP: registros de acceso con identificadores de empleados, sin retención | Fijar retención de los *event logs* del IdP junto con la decisión de retención general (D-11) |
| RG-5 | Cambiar de IdP por sus funciones y pagarlo en código | Si se cambia, hacerlo antes de tener clientes en producción: después el coste se multiplica |

## 7. Qué no se verificó

No se ejecutó nada (`mvn`, `kubectl`, tests); no hay Keycloak ni clúster accesibles en esta sesión; no se probó ningún token real; no se instaló ninguna de las tres alternativas — todo lo dicho de ellas viene de su documentación oficial, citada con URL y fecha. Las cifras de coste operativo son estimaciones para comparar órdenes de magnitud, no un presupuesto.

---

Términos de este documento: ver [`GLOSSARY.md`](../GLOSSARY.md) (IdP, OIDC, `aud`, `client_credentials`, fail-open/fail-closed).
