# ADR-0001 — Transporte HTTP hacia SAP: `RestClient` de Spring

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-12 |
| **Decisión del plan** | D-1 ([`../../auditorias/2026-09-10-plan-de-accion.md`](../../auditorias/2026-09-10-plan-de-accion.md)) |
| **Spec afectado** | [`../../sdd/common/resiliencia-cliente-sap.md`](../../sdd/common/resiliencia-cliente-sap.md) |
| **Reevaluar cuando** | el SAP Cloud SDK for Java publique soporte oficial de Spring Boot 4 (ver §5) |

## 1. Contexto

El cliente HTTP hacia SAP (`SapClient` en `common/sap`) estaba implementado con
**WebClient** reactivo (Reactor Netty) y cada llamada terminaba en `.block()`:
toda la aplicación es síncrona (listeners Kafka, JPA, virtual threads), así que
la pila reactiva no aportaba nada y sí costaba: `spring-boot-starter-webflux`,
`reactor-netty` y `resilience4j-reactor` en el classpath, dos modelos de
ejecución que entender, y métricas HTTP del cliente que nunca se registraban
porque el `WebClient.builder()` era estático (auditoría, Fase 5).

Además, `common` dependía de `sdk-core` del **SAP Cloud SDK** y ambas apps
escaneaban `com.sap.cloud.sdk` sin que ninguna llamada real pasara por el SDK:
solo existía un destino local de prueba (`SapCloudSdkLocalDestinationConfig`)
que nadie consumía. El SDK 5.x declara soporte de Spring Boot 3; con Boot 4
funcionaba de forma incidental.

La auditoría preguntó qué transporte debía ser el definitivo. Hubo dos
candidatos serios.

## 2. Opciones

| | `RestClient` (Spring) | VDM del SAP Cloud SDK |
|---|---|---|
| Qué es | Cliente HTTP síncrono de Spring 6.1+, misma API fluida que `WebClient` sin Reactor | Cliente OData **tipado** generado desde los metadatos de S/4 (`BusinessPartnerService`, `A_BusinessPartner`...) |
| OData V2 | Lo escribimos nosotros: `x-csrf-token`, `If-Match`, deep insert, envoltura `d` | Lo trae hecho: CSRF, ETag, `$batch`, deep insert, paginación |
| Destinos y auth BTP | Nuestros `SapAuthProvider` (OAuth2/basic) | Destination Service de BTP integrado |
| Soporte Spring Boot 4 | Nativo | **No declarado** a fecha de hoy |
| Coste de adopción | Cambiar la implementación detrás de `SapClient`; el resto no cambia | Reescribir los adaptadores S/4 sobre el VDM; los BTP no encajan |
| Dependencias | `spring-web` (ya presente vía `starter-web`) | `sdk-core`, `odata-v2-core`, generador VDM, sus BOMs |
| Riesgo | Reimplementar bien las convenciones OData V2 (Fase 3) | Quedar anclados a Boot 3 o a un soporte no oficial |

## 3. Decisión

**`RestClient` ahora.** El transporte se cambia **detrás del puerto**
`SapClient`: `RestClientSapClient` sustituye a `WebClientSapClient` con la misma
semántica observable (retry en 5xx, no en 4xx, circuit breaker por fuera del
retry, CSRF con refresh único). El test del antiguo cliente se portó íntegro y
sigue verde: es la prueba de que el cambio es de implementación.

Se retiran del runtime: `spring-boot-starter-webflux`, `resilience4j-reactor`,
`sdk-core`, el `@ComponentScan("com.sap.cloud.sdk")` de ambas apps, el destino
local del SDK y la propiedad `sap.cloud-sdk.local-destination.enabled`. El BOM
del Cloud SDK **se conserva en el parent** porque `sap-api-models` usa su
`openapi-generator-maven-plugin` y `openapi-core` para generar los POJOs de las
specs oficiales: es una dependencia de *modelos*, no de *conectividad*.

Sobre el `HttpClient` del JDK (`JdkClientHttpRequestFactory`): soporta `PATCH`
(`HttpURLConnection` no), HTTP/2 y timeouts de conexión y lectura sin librerías
adicionales.

## 4. Consecuencias

- **Lo que ganamos ya**: un solo modelo de ejecución, menos classpath, `PATCH` y
  `DELETE` de primera clase, y el cliente HTTP instrumentable por Micrometer
  cuando la Fase 5 lo construya vía `RestClient.Builder` de Boot.
- **Lo que asumimos**: `If-Match`/ETag, deep insert y la envoltura `d` de
  OData V2 los implementamos nosotros en la Fase 3 (upsert idempotente). Cada
  convención va con su contract test contra WireMock y se valida contra el
  tenant de test.
- **Lo que se corrigió de paso** porque tocaba el mismo código: el fetch CSRF
  usa la misma cabecera `Authorization` que la escritura (antes Basic fijo), un
  403 solo es rechazo CSRF si trae `x-csrf-token: Required`, y token y cookies
  se cachean como un único valor inmutable (auditoría A6/C4).
- **Lo que no cambia**: `SapClient`, `SapOutboundPort`, los adaptadores y los
  use cases. `docs/tools-integrations/SAP_CLOUD_SDK.md` pasa a ser la guía de la
  opción descartada por ahora.

## 5. Disparador de reevaluación

Esta decisión **se revisa**, no es definitiva. Se reabre cuando ocurra cualquiera
de estas dos cosas:

1. El SAP Cloud SDK for Java anuncie **soporte oficial de Spring Boot 4** en su
   [release notes](https://sap.github.io/cloud-sdk/docs/java/release-notes).
2. La Fase 3 demuestre que reimplementar las convenciones OData V2 (ETag, deep
   insert, `$batch`) supera el coste de adoptar el VDM.

Al reabrirla, la migración es local: una nueva implementación de `SapClient`
sobre el VDM, los mismos contract tests y el mismo spec.
