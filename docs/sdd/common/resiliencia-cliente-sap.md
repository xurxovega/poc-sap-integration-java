# Cliente SAP: transporte, resiliencia y CSRF

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la usan todos los adaptadores `Sap*Adapter`) |
| **Estado** | ✅ implementado |
| **Entradas** | llamadas de los adaptadores `SapOutboundPort` de cada dominio |
| **Destino SAP** | BTP y S/4 nativo |
| **Última revisión** | 2026-09-18 |

## 1. Objetivo

Que **toda** llamada HTTP hacia SAP, de cualquier dominio, se comporte igual
ante fallos: reintente lo transitorio, no reintente lo que es error nuestro,
deje de llamar cuando SAP está caído, y lleve siempre la autenticación, la
idempotencia y el token CSRF que SAP exige. Un adaptador de feature no toma
ninguna de esas decisiones: delega en el puerto `SapClient`.

## 2. Alcance

**Dentro**: semántica de errores (5xx / 4xx / transporte / circuito abierto),
retry con backoff, circuit breaker, timeouts, cabeceras comunes
(`Authorization`, `Accept`, `Content-Type`, `Idempotency-Key`), flujo CSRF de
OData V2 (fetch, cookies, refresh en 403), métodos GET / POST / PATCH / DELETE.

**Fuera** (y por qué):
- Qué se envía (mapeo campo a campo): es de cada feature
  (`customer/sincronizacion-*.md`, `article/sincronizacion-articulo.md`).
- Obtención del token OAuth2 y elección OAuth2/basic por destino:
  `SapAuthProvider` (spec pendiente, hoy en `TECH.md` §8).
- Upsert idempotente (verificación previa + alta o actualización): tiene spec
  propio, [`upsert-idempotente-sap.md`](upsert-idempotente-sap.md). Aquí solo
  vive el **transporte** de la precondición (`If-Match`, `ETag`) y la política de
  reintento que la hace segura.
- El transporte concreto (WebClient, RestClient, VDM) **no es comportamiento
  observable**: se decide en [`../../architecture/adr/0001-transporte-http-sap-restclient.md`](../../architecture/adr/0001-transporte-http-sap-restclient.md).

## 3. Entrada

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `destination` | `BTP` \| `S4_NATIVE` | sí | Elige base URL, `SapAuthProvider` y si aplica CSRF. Un destino sin provider configurado es un error de programación (`IllegalArgumentException`), no un `SAP_ERROR` |
| `path` | ruta relativa | sí | Puede llevar query OData (`?$top=1&$filter=...`) y claves `Entidad('id')` |
| `entityId` | texto | en escrituras | Solo para log y trazas |
| `payloadHash` | texto | en escrituras | Viaja como `Idempotency-Key` |
| `body` | JSON ya mapeado | en POST/PATCH | Si es `null` se envía `{}` |
| `ifMatch` | `ETag` | no | Solo PATCH y DELETE. Si viene, viaja como cabecera `If-Match` y **cambia la política de reintento**: convierte la llamada en idempotente (R-1). Si es `null` o vacío, la cabecera no se envía |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | **Reintentar depende del método y de la fase del fallo, no solo de si es transitorio.** En una llamada **idempotente** (`GET`, `DELETE`, o `PATCH` **con** `If-Match`) un 5xx o un error de transporte se reintentan con backoff exponencial hasta `sap.client.retry.max-attempts`. En una **escritura no idempotente** (`POST`, o `PATCH` sin `If-Match`) **solo** se reintenta el fallo ocurrido *antes de enviar* (R-8), hasta `sap.client.retry.write.max-attempts`: un timeout de respuesta o un 5xx **no se reintentan jamás**, porque SAP pudo haberlos aplicado. Agotados los intentos se devuelve el último status (o `0` si fue transporte) | Reintentar un `POST` cuya respuesta se perdió crea hasta 3 Business Partners duplicados (hallazgo 2B-3); no reintentar lo transitorio manda a la DLT mensajes recuperables |
| R-2 | Un **4xx** es error de contrato o de datos: **no se reintenta** y se devuelve tal cual con su cuerpo, para diagnóstico | — |
| R-3 | El **circuit breaker envuelve al retry**, no al revés. Con el circuito abierto no se hace ninguna llamada y se lanza `SapCircuitOpenException` (transitoria): el listener Kafka la trata como **reintentable declarada** y espera con un **backoff propio que cubre la ventana de apertura** (`app.kafka.retry.circuit-open-backoff-ms`, alineado con `sap.client.circuit-breaker.wait-duration-open-ms`), no con el backoff normal. Nunca se devuelve una respuesta falsa (`status 0`) que el use case tomaría por un `SAP_ERROR` normal | Auditoría B13: el circuito abierto se tragaba en silencio y se reintentaba 3 veces. Con el backoff normal (1+2+4 s = 7 s < 30 s) el mensaje llegaba a la DLT con SAP aún caído |
| R-4 | Toda escritura a **S/4 nativo** con CSRF activo (`sap.s4.csrf.enabled`) lleva `x-csrf-token` y las cookies de sesión del fetch. El fetch se autentica con la **misma** cabecera `Authorization` que la escritura. Token y cookies se cachean **juntos** y se invalidan juntos | Auditoría A6/C4: el fetch iba con Basic fijo aunque el destino fuera OAuth2; token y cookies se leían por separado sin sincronizar |
| R-5 | Solo es **rechazo CSRF** un `403` que además trae `x-csrf-token: Required`. Entonces se invalida el token, se refresca y se reintenta **una** vez. Un `403` sin esa cabecera es falta de autorización: se devuelve tal cual (R-2) | Auditoría C4: cualquier 403 se trataba como CSRF y se reintentaba |
| R-6 | Toda petición lleva `Authorization` del `SapAuthProvider` del destino, `Accept: application/json`; las escrituras además `Content-Type: application/json` e `Idempotency-Key = payloadHash`. La `Location` de la respuesta se conserva en `SapResponse` | — |
| R-7 | `Idempotency-Key` **no es garantía** en OData V2 de S/4: SAP la ignora. La idempotencia real la dan el dedupe por `payloadHash` (spec [`idempotencia-y-dedupe.md`](idempotencia-y-dedupe.md)) y la verificación previa ([`upsert-idempotente-sap.md`](upsert-idempotente-sap.md)) | Creer que la cabecera protege lleva a duplicados en SAP |
| R-8 | Un fallo de transporte se clasifica en **antes de enviar** (conexión rechazada, timeout de *conexión*, host que no resuelve, sin ruta) o **tras enviar** (timeout de *respuesta*, conexión reseteada, error de E/S, TLS). Se recorre la **cadena de causas completa**, porque Spring envuelve el fallo del `HttpClient` del JDK. Lo que no se reconoce es *desconocido* y las escrituras lo tratan como «tras enviar». Un fallo de DNS (`UnresolvedAddressException`, que hereda de `IllegalArgumentException`) es transporte, **no** un error de programación: se reintenta y cuenta para el circuit breaker | Clasificar mal hacia «antes de enviar» duplica datos maestros; hacia «tras enviar» solo obliga a reenviar con el siguiente evento. La asimetría del coste manda. Antes, un DNS con hipo mandaba el mensaje a la DLT sin un solo reintento |
| R-9 | Un `PATCH` o un `DELETE` pueden llevar **precondición**: la cabecera `If-Match` con el `ETag` leído en el `GET` inmediatamente anterior. La respuesta conserva el `ETag` que devuelve SAP, en cabecera o en `__metadata.etag`. Un `412 Precondition Failed` significa que el recurso cambió desde nuestra lectura y **obliga a releer antes de reintentar** | Sin precondición, dos procesos que actualicen a la vez se pisan en silencio; con un `ETag` viejo, el 412 es la señal correcta y no un error |

## 5. Salida

`SapResponse(httpStatus, body, location, etag)`, normalizada para todos los destinos y
métodos. `isSuccess()` ⇔ `2xx`. Ninguna excepción HTTP sale del cliente salvo
`SapCircuitOpenException` (R-3) e `IllegalArgumentException` (destino no
configurado).

## 6. Estados y errores

El cliente no toca la máquina de estados; el use case traduce su respuesta:

| Situación | `SapResponse` / excepción | Estado que fija el use case | Reintentable |
|---|---|---|---|
| 2xx | status real | `SENT_SAP` | — |
| 4xx | status real + cuerpo | `SAP_ERROR` | con un evento nuevo (datos corregidos) |
| 5xx agotados los reintentos | último status | `SAP_ERROR` | con un evento nuevo; la Fase 1 garantiza que re-entra |
| Transporte agotado | `status 0` | `SAP_ERROR` | ídem |
| **5xx en una escritura** (`POST`, `PATCH` sin `If-Match`) | status real, **un solo intento** | `SAP_ERROR` | con un evento nuevo. **Nunca se reintenta en caliente**: SAP pudo haberla aplicado |
| **Fallo antes de enviar en una escritura** | tras `sap.client.retry.write.max-attempts` intentos, `status 0` | `SAP_ERROR` | ídem |
| **Verificación previa no concluyente** | `SapLookupUnavailableException` (no se escribe nada) | `COMMUNICATION_ERROR` | sí: SAP no se tocó, la reentrega es segura. Ver [`upsert-idempotente-sap.md`](upsert-idempotente-sap.md) |
| `412` en un `PATCH` con `If-Match` | status real | un re-lookup + un PATCH; al segundo 412, `SAP_ERROR` | — |
| Circuito abierto | `SapCircuitOpenException` | ninguno: la excepción sube al listener | sí, backoff Kafka |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado SAP respondiendo 5xx, cuando se envía, entonces se reintenta hasta el éxito o hasta agotar intentos, y en ese caso se devuelve el último 5xx | `RestClientSapClientTest#retriesOn5xxUntilSuccess` · `#exhaustedRetriesReturnLastServerError` |
| AC-2 | Dado SAP respondiendo 4xx, cuando se envía, entonces se hace una sola llamada y se devuelve status y cuerpo | `RestClientSapClientTest#clientErrorIsNotRetried` |
| AC-3 | Toda escritura lleva `Authorization`, `Accept`, `Content-Type`, `Idempotency-Key` y el cuerpo intacto; PATCH y DELETE pasan por el mismo transporte y conservan `Location` | `RestClientSapClientTest#sendsAuthAcceptAndIdempotencyHeaders` · `#patchAndDeleteGoThroughTheSameTransport` |
| AC-4 | Dado el circuito abierto, cuando se envía, entonces no se llama a SAP y se lanza `SapCircuitOpenException` | `RestClientSapClientTest#openCircuitRaisesCircuitOpenExceptionInsteadOfFakeResponse` |
| AC-5 | Dada una escritura a S/4 con CSRF activo, entonces va precedida de un fetch y lleva `x-csrf-token` y las cookies del fetch | `RestClientSapClientTest#writesToS4CarryCsrfTokenAndCookies` |
| AC-6 | El fetch CSRF lleva la misma cabecera `Authorization` que la escritura (basic u OAuth2 según el destino) | `RestClientSapClientTest#csrfFetchUsesTheDestinationAuthorizationHeader` |
| AC-7 | Un 403 con `x-csrf-token: Required` refresca y reintenta una vez; un 403 sin esa cabecera se devuelve tal cual sin refrescar | `RestClientSapClientTest#csrfRejectionRefreshesTokenAndRetriesOnce` · `#plainForbiddenIsNotTreatedAsCsrfRejection` |
| AC-8 | Los adaptadores reales de cada dominio, cableados sobre este cliente, emiten el método, path, cabeceras y cuerpo esperados contra WireMock | `it/…/contract/*ContractTest` (failsafe) |
| AC-9 | Dada una escritura (`POST`) cuya respuesta se pierde por *timeout de respuesta*, cuando el cliente la ejecuta, entonces SAP recibe **exactamente una** petición y se devuelve `status 0` | `RestClientSapClientTest#postIsNotRetriedAfterAResponseTimeout` |
| AC-10 | Dada una escritura que falla **antes de salir** (conexión rechazada, host no resuelto), entonces se reintenta hasta `sap.client.retry.write.max-attempts` | `RestClientSapClientTest#postIsRetriedWhenTheConnectionWasRefusedBeforeSending` · `#unresolvedHostIsRetriedNotTreatedAsAProgrammingError` |
| AC-11 | Dada una escritura que recibe un 5xx, entonces **no** se reintenta y el fallo cuenta igual para el circuit breaker | `RestClientSapClientTest#postIsNotRetriedOnServerError` · `#serverErrorOnAWriteStillFeedsTheCircuitBreaker` |
| AC-12 | Dado un `GET`, entonces la política de reintento anterior se mantiene íntegra (5xx y transporte) | `RestClientSapClientTest#getIsStillRetriedOnServerError` · `#retriesOn5xxUntilSuccess` · `#exhaustedRetriesReturnLastServerError` |
| AC-13 | Un `PATCH` con `If-Match` se reintenta como una lectura; sin `If-Match`, solo antes de enviar | `RestClientSapClientTest#patchWithIfMatchIsRetriedLikeAnIdempotentCall` |
| AC-14 | Un `PATCH`/`DELETE` con precondición envía la cabecera `If-Match` con el ETag indicado, la respuesta conserva el `ETag`, y sin precondición no se envía la cabecera | `RestClientSapClientTest#patchWithIfMatchSendsThePreconditionHeader` · `#responseKeepsTheEtag` · `#withoutPreconditionNoIfMatchHeaderIsSent` |
| AC-15 | La clasificación del fallo de transporte distingue timeout de **conexión** (antes de enviar) de timeout de **respuesta** (tras enviar), incluso anidados en la cadena de causas de Spring, y no confunde un fallo de DNS con un error de programación | `TransportFailuresTest#connectTimeoutIsBeforeSendAndReadTimeoutIsAfterSend` · `#theCauseChainIsWalkedToTheBottom` · `#unknownFailuresAreTreatedAsAfterSend` · `#unresolvedAddressIsTransportNotAProgrammingError` · `#connectionRefusedOnTheRealStackIsBeforeSend` · `#responseTimeoutOnTheRealStackIsAfterSend` · `#unresolvedHostOnTheRealStackIsBeforeSend` |
| AC-16 | Dado el circuito abierto, cuando el listener Kafka recibe `SapCircuitOpenException`, entonces la trata como reintentable **declarada** y espera con el backoff largo (≥ la ventana de apertura), no con el normal | `KafkaErrorHandlingConfigTest#circuitOpenIsDeclaredRetryable` · `#circuitOpenUsesTheLongerBackOff` |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

- Log `WARN` al refrescar CSRF y al encontrar el circuito abierto; `ERROR` con
  método, destino, `entityId` y status al agotar reintentos.
- Métricas de Resilience4j (`resilience4j_retry_calls`, `..._circuitbreaker_state`)
  y del cliente HTTP (`http.client.requests`): **pendientes** de cablear el
  binder y construir el cliente vía `RestClient.Builder` de Boot (plan, Fase 5).

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| Puerto | `common/sap/SapClient.java` | — |
| R-1, R-2, R-3, R-6 | `common/sap/RestClientSapClient.java` (`exchange`, `doExchange`, `retryFor`, `SapServerException`) | `RestClientSapClientTest` AC-1..AC-4, AC-9..AC-13 |
| R-8 | `common/sap/TransportFailures.java` | `TransportFailuresTest` AC-15 |
| R-9 | `common/sap/SapClient.java` (`patch`/`delete` con `ifMatch`) · `RestClientSapClient.RawResponse.toSapResponse` | AC-14 |
| R-3 excepción | `common/sap/SapCircuitOpenException.java` | AC-4 |
| R-3 backoff Kafka, AC-16 | `common/kafka/KafkaErrorHandlingConfig.java` (`RETRYABLE`, `circuitOpenBackOff`, `backOffFunction`) · `app.kafka.retry.*` | `KafkaErrorHandlingConfigTest` AC-9 |
| R-4, R-5 | `RestClientSapClient.applyCsrf` / `isCsrfRejection` · `common/sap/odata/CsrfTokenProvider.java` · `S4CsrfTokenProvider.java` | AC-5..AC-7 |
| Configuración (`sap.client.*`, `sap.s4.csrf.*`) | `common/sap/SapIntegrationConfig.java` · `application-common.yml` | `*ApplicationContextTest` (arranque) |
| AC-8 | adaptadores `customer/adapters/sap/*`, `article/adapters/sap/*` | `it/…/contract/*ContractTest` |
| Transporte (decisión) | [ADR-0001](../../architecture/adr/0001-transporte-http-sap-restclient.md) | — |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-18 | **R-1 reescrita**: reintentar depende del método y de la fase del fallo, no solo de si es transitorio. Un 5xx en un `POST` ya no se reintenta (2B-3). R-8 (clasificación antes/tras enviar; el fallo de DNS deja de ser «error de programación») y R-9 (`If-Match`/`ETag`/412) nuevas. §3 con la columna `ifMatch`, §5 con `etag`, §7 AC-9..AC-15 (el AC del backoff Kafka pasa a AC-16), §9 al día. El upsert sale de «Fuera» y pasa a tener spec propio | — |
| 2026-09-18 | R-3 ampliada y **AC-9**: `SapCircuitOpenException` pasa a ser reintentable **declarada** en `KafkaErrorHandlingConfig` y con backoff propio (`app.kafka.retry.circuit-open-backoff-ms`, 30 s) que cubre la ventana de circuito abierto; antes lo era por omisión y los 7 s del backoff normal no llegaban ([ADR-0011](../../architecture/adr/0011-concurrencia-entre-instancias-fencing-sin-lease.md)) | — |
| 2026-09-12 | Spec inicial, escrito al cambiar el transporte a `RestClient` (ADR-0001, plan Fase 3.1). Recoge el comportamiento que ya existía (AC-1..AC-5, AC-8) y fija dos correcciones de la auditoría: el fetch CSRF usa la auth del destino (AC-6) y solo un 403 con `Required` es rechazo CSRF (AC-7). R-7 documenta que `Idempotency-Key` no es garantía en OData V2 | — |
