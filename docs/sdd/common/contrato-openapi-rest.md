# Contrato REST publicado de cada módulo (OpenAPI)

| | |
|---|---|
| **Dominio** | `common` (capacidad transversal; la cumple cada módulo con controladores REST: `customer`, `article`) |
| **Estado** | ✅ implementado |
| **Entradas** | ninguna en runtime: es un artefacto de documentación que viaja con el jar |
| **Destino SAP** | ninguno |
| **Última revisión** | 2026-09-18 |

## 1. Objetivo

Que cualquiera —un compañero, un cliente externo, un agente— pueda saber qué
expone cada servicio y probarlo **sin leer el código**: un fichero estándar que
se importa en Postman, Bruno o Swagger UI, se apunta al entorno local o al de
test, se le pone el token y ya se puede llamar. Y que ese fichero **no pueda
quedarse viejo**: si el código y el contrato divergen, el build falla.

Hasta ahora lo único compartible era la colección de Postman
([`../../../scripts/postman/`](../../../scripts/postman/)), que nadie vigilaba.

## 2. Alcance

**Dentro**: un `openapi.yml` por módulo con controladores REST; rutas, métodos,
parámetros, cuerpos, códigos de respuesta y ejemplos; el rol mínimo de cada
operación y si su respuesta se enmascara para lectura externa; los servidores
(local y test); el test que vigila que contrato y controladores no divergen.

**Fuera** (y por qué):

- **Servirlo por HTTP** (`/openapi.yml`, Swagger UI embebido): exigiría abrir una
  ruta sin autenticar en `ApiSecurityConfig` y es otra decisión. Hoy el fichero
  viaja dentro del jar y se comparte como fichero.
- **Generarlo en runtime** (springdoc): sería una dependencia más y un contrato
  que solo existe con la aplicación arrancada. Aquí el contrato es fuente, no
  salida.
- **Sustituir a los specs de feature**: el contrato dice *cómo se llama*, los
  specs de `customer/` y `article/` dicen *qué hace y bajo qué reglas*.
- **Los contratos de SAP**: esos son de SAP y viven en `sap-api-models/specs/`
  ([`../sap-api-catalog.md`](../sap-api-catalog.md)).
- **Validar peticiones contra el esquema** en runtime: no se hace.

## 3. Entrada

No tiene entrada de runtime. Los ficheros son:

| Fichero | Contenido |
|---|---|
| `customer/src/main/resources/openapi.yml` | contrato REST del servicio de clientes (puerto 8081) |
| `article/src/main/resources/openapi.yml` | contrato REST del servicio de artículos (puerto 8082) |

## 4. Reglas de negocio

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | **Cada módulo con controladores REST publica un `openapi.yml`** en `src/main/resources/`, para que viaje dentro del jar desplegado y pueda servirse más adelante sin moverlo de sitio | El servicio no tiene contrato compartible |
| R-2 | El contrato es **OpenAPI 3.1** en YAML, con `info` (título, versión = la del `pom.xml`, descripción en español) | No se importa limpio en las herramientas |
| R-3 | Declara **dos servidores**: el local (`http://localhost:8081` / `:8082`) y el de test como `https://{host}` con una variable `host` de valor por defecto de relleno, porque todavía no hay Ingress definido (auditoría 2A-15) | No se puede elegir entorno al importarlo |
| R-4 | Declara `components.securitySchemes.keycloak` (bearer JWT) y lo aplica **globalmente** con `security` | Quien importa el contrato no sabe que hace falta token |
| R-5 | **Toda operación declara `x-required-role`** con el rol o roles que exige su `@PreAuthorize`, y `x-masked-for-external-read` para decir si la respuesta va enmascarada para `sap-external-read` ([`seguridad-api.md`](seguridad-api.md) R-4) | El contrato no diría quién puede llamar a qué |
| R-6 | El contrato describe **todos** los endpoints del módulo y **solo** esos: cada método+ruta de los controladores está en `paths`, y cada operación de `paths` existe en un controlador | Contrato incompleto o inventado |
| R-7 | Cada operación lleva al menos **un ejemplo** de petición (si tiene cuerpo) y uno de respuesta correcta, y declara los códigos que puede devolver: `401` y `403` siempre; `400` y `409` en las de escritura de `customer`, con el `409` en `application/problem+json`; `404` en las consultas de histórico | Los ejemplos son lo que hace el contrato *probable* sin leer código |
| R-8 | **El contrato lo vigila un test por módulo**: `OpenApiMatchesControllersTest` descubre los endpoints por reflexión sobre `bootstrap.web` y compara con el YAML. Añadir, quitar o cambiar el rol de un endpoint sin tocar el contrato rompe el build, diciendo qué endpoint y dónde | Volvería a ser documentación que envejece sola |
| R-9 | Una respuesta cuyo contenido el código no fija campo a campo —un mapa de clave variable, como `changes` del diff o `features` del estado— se documenta con `additionalProperties` sobre el esquema del valor, **nunca inventando claves** | Contrato que miente |

## 5. Salida

El fichero en sí. Lo que declara hoy, por módulo:

### `customer` (puerto 8081)

| Método | Ruta | Rol (`x-required-role`) | Respuesta correcta | Otros códigos |
|---|---|---|---|---|
| `POST` | `/customers/sync` | `SAP_WRITE` | `200` `{entityId, state}` | 400, 401, 403, **409** (`problem+json`) |
| `POST` | `/customers/validate` | `SAP_WRITE` | `200` `{entityId, state}` | 400, 401, 403, 409 |
| `GET` | `/customers/{id}/history?full=` | `SAP_READ` · `SAP_EXTERNAL_READ` | `200` `{entityId, versions[], masked}` — **enmascarada** para lectura externa | 401, 403, 404 |
| `GET` | `/customers/{id}/history/diff?from=&to=` | `SAP_READ` | `200` `{entityId, from, to, changes}` | 401, 403, 404 |
| `GET` | `/customers/{id}/state` | `SAP_READ` · `SAP_EXTERNAL_READ` | `200` estado del cliente y de cada parte + traza del ciclo — **enmascarada** para lectura externa | 401, 403 |

### `article` (puerto 8082)

| Método | Ruta | Rol (`x-required-role`) | Respuesta correcta | Otros códigos |
|---|---|---|---|---|
| `POST` | `/articles/sync` | `SAP_WRITE` | `200` `{entityId, state}` | 400, 401, 403 |
| `GET` | `/articles/{id}/history?full=` | `SAP_READ` · `SAP_EXTERNAL_READ` | `200` `{entityId, versions[]}` (sin enmascarar: el artículo no tiene datos personales) | 401, 403, 404 |
| `GET` | `/articles/{id}/history/diff?from=&to=` | `SAP_READ` · `SAP_EXTERNAL_READ` | `200` `{entityId, from, to, changes}` | 401, 403, 404 |

Mapas de clave variable documentados con `additionalProperties` (R-9):
`HistoryDiff.changes` (una entrada por ruta cambiada) y `EntityState.features`
(una entrada por parte con historial).

### Cómo se prueba contra el entorno de test

1. Importar `<modulo>/src/main/resources/openapi.yml` en Postman, Bruno o Swagger
   UI (o abrirlo en un editor OpenAPI).
2. Elegir el servidor: `localhost` para local; para test, rellenar la variable
   `host` con el host real del servicio, o hacer `kubectl port-forward` y usar
   el servidor local.
3. Poner el token de Keycloak como *bearer*
   ([`../../tools-integrations/KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md)),
   con un usuario que tenga el rol que pide `x-required-role`.

La colección de Postman de
[`../../../scripts/postman/`](../../../scripts/postman/) sigue siendo válida y es
complementaria: además de la API trae las llamadas al SAP simulado.

## 6. Estados y errores

No toca la máquina de estados. El único "error" de esta capacidad es de
construcción: el build falla si contrato y controladores divergen.

| Situación | Resultado | Reintentable |
|---|---|---|
| Endpoint nuevo sin dar de alta en el contrato | build en rojo, con el método, la ruta y la clase que lo declara | — |
| Operación en el contrato sin controlador que la atienda | build en rojo, con la operación fantasma | — |
| `x-required-role` que no coincide con el `@PreAuthorize` | build en rojo, con lo que dice cada uno | — |
| Contrato sin `servers` o sin `securitySchemes.keycloak` | build en rojo | — |

## 7. Criterios de aceptación

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado un módulo con controladores REST, cuando un endpoint del código no está en `paths` del contrato, entonces el build falla diciendo el método, la ruta y la clase | `OpenApiMatchesControllersTest#everyControllerEndpointIsDeclaredInTheContract` (customer y article) |
| AC-2 | Dado el contrato, cuando declara una operación que ningún controlador atiende, entonces el build falla nombrándola | `OpenApiMatchesControllersTest#everyContractOperationExistsInAController` (×2) |
| AC-3 | Dada una operación del contrato, entonces declara `x-required-role` y coincide exactamente con los roles del `@PreAuthorize` del método; si falta o difiere, el build falla diciendo qué dice cada lado | `OpenApiMatchesControllersTest#everyOperationDeclaresTheRoleItsPreAuthorizeDemands` (×2) |
| AC-4 | El contrato es OpenAPI 3.1, lleva `info`, al menos dos `servers` con `url`, `components.securitySchemes.keycloak` y `security` global | `OpenApiMatchesControllersTest#theContractDeclaresVersionServersAndSecurityScheme` (×2) |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Ninguna en runtime: el contrato no se sirve ni se carga al arrancar. Su única
señal es el resultado del build.

## 9. Trazabilidad spec ↔ código

| Elemento del spec | Código | Test |
|---|---|---|
| R-1, R-2, R-3, R-4, R-7, §5 | `customer/src/main/resources/openapi.yml` · `article/src/main/resources/openapi.yml` | `OpenApiMatchesControllersTest` ×2 |
| R-5 (rol por operación) | `x-required-role` ↔ `@PreAuthorize` de `customer/bootstrap/web/*Controller`, `article/bootstrap/web/*Controller` ([`seguridad-api.md`](seguridad-api.md) R-3) | `OpenApiMatchesControllersTest#everyOperationDeclaresTheRoleItsPreAuthorizeDemands` |
| R-6, R-8 | `customer/src/test/java/com/poc/sap/customer/bootstrap/web/OpenApiMatchesControllersTest.java` · homólogo en `article` | ellos mismos |
| R-9 | `HistoryDiff.changes` (`JsonDiff.Change`) · `EntityState.features` (`CustomerStateUseCase.LineState`) | — |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| 2026-09-18 | Spec inicial. Cada módulo con controladores REST publica su contrato OpenAPI 3.1 en `src/main/resources/openapi.yml`, con el rol de cada operación y el enmascarado para lectura externa, y un test por módulo impide que contrato y código diverjan | — |
