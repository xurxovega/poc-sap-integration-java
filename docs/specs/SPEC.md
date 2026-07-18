# SAP Integration — Especificación funcional

> Documento **agnóstico a la tecnología**. Define objetivo, alcance, dominios,
> contratos de entrada/salida, modelo de despliegue y criterios de aceptación.
> La tecnología concreta se documenta en [`TECH.md`](./TECH.md).

## 1. Objetivo

Sincronizar datos maestros (customer, article, supplier) desde sistemas legacy
hacia **SAP S/4 Public Cloud**, con validación de negocio, trazabilidad de
estado por registro, idempotencia y observabilidad, permitiendo el **despliegue
independiente de cada dominio**.

## 2. Contexto y alcance

### En alcance

- Captura de cambios desde bases de datos legacy mediante **CDC** (outbox + Debezium).
- Validación de negocio e indexación (imagen actual + histórico).
- Envío a APIs SAP: **BTP** y **nativas S/4 Public Cloud**.
- **API REST síncrona** como entrada alternativa.
- **Consumo de eventos Kafka directos** como entrada alternativa.
- Trazabilidad por registro mediante máquina de estados.

### Fuera de alcance (futuro)

- Orquestación tipo **saga** entre dominios.
- GraphQL y notificaciones WebSocket.
- Event sourcing completo (sí historización en índice).

## 3. Dominios funcionales

Dominios = bounded contexts. Cada uno se despliega de forma independiente.

| Dominio   | Entidades             | Features                                                |
|-----------|-----------------------|---------------------------------------------------------|
| customer  | Customer, Mandate     | sync, validate, index, delete_customer, delete_mandate |
| article   | Article               | sync, validate, index                                   |
| supplier  | Supplier              | (futuro) sync, validate, index                          |

## 4. Fuentes de entrada (ingesta)

Tres fuentes equivalentes que alimentan el **mismo caso de uso** del dominio,
intercambiables mediante un puerto `IngestionPort`:

1. **CDC (actual)**: triggers legacy → tabla outbox → Debezium → topic
   `outbox.<DOMINIO>`.
2. **Eventos Kafka directos (futuro)**: productores propios publican en
   `events.<DOMINIO>`.
3. **API REST (futuro)**: `POST /{domain}/sync` síncrono.

Contrato común del mensaje de entrada:

- Identificador de entidad.
- Tipo de operación (`create` / `update` / `delete`).
- Payload de la entidad.
- Origen (`cdc` / `kafka` / `rest`).
- Hash de idempotencia (sobre payload + identificador).

## 5. Destinos SAP

Salida mediante puerto `SapOutboundPort` con dos familias de adaptadores:

- **APIs BTP**: vía Destination Service / xsuaa (OAuth2).
- **APIs nativas S/4 Public Cloud**: OData/REST propio con autenticación propia.

Cada dominio declara **qué entidad se envía a qué destino** y con qué mapeo.
Los mapeos son parte del dominio, no del shared kernel.

## 6. Modelo de despliegue y versionado

- **Un artefacto desplegable por dominio** (`customer-app`, `article-app`,
  `supplier-app`). Modificar un dominio implica desplegar **solo ese artefacto**.
- **Shared kernel (librería común)**: primitivas de dominio, máquina de estados,
  clientes SAP, observabilidad y soporte de test.
  - Versionado semántico.
  - Un cambio en `common` **puede requerir re-desplegar todos los dominios** →
    por ello `common` exige **pruebas de integración estrictas** y evolución
    backward-compatible por defecto.
- Criterio de re-despliegue:

  | Cambio en `common` | Re-despliegue de dominios |
  |--------------------|---------------------------|
  | `patch`            | Opcional                  |
  | `minor`            | Recomendado               |
  | `major`            | Obligatorio               |

## 7. Requisitos no funcionales

- **Idempotencia**: por hash de payload + identificador de entidad; los reintentos
  no duplican envíos a SAP.
- **Resilencia**: retry con backoff exponencial y circuit breaker hacia SAP.
- **Trazabilidad**: cada registro pasa por la máquina de estados (§8) y se
  persiste cada transición.
- **Observabilidad**: métricas (contador/latencia por estado y dominio), logs
  estructurados y trazas distribuidas.
- **Rendimiento**: procesamiento concurrente por dominio; throughput configurable
  por dominio.
- **Seguridad**: secretos fuera del código; credenciales SAP rotativas; sin logs
  de secretos.

## 8. Trazabilidad y estados

Máquina de estados por registro (dominio-agnóstica, reside en `common`):

```
RECEIVED → FETCHING → VALIDATING → VALID | INVALID
                                   VALID → INDEXING → INDEXED
                                                   INDEXED → SENDING_SAP → SENT_SAP
Errores: ERROR, SAP_ERROR, COMMUNICATION_ERROR
```

Cada transición se persiste con `timestamp`, `origen` y `hash`.

## 9. Criterios de aceptación / resultados

- Cada dominio procesa CDC, eventos y REST por el **mismo pipeline**.
- Envío a SAP **idempotente** verificable por hash.
- Estado de cada registro **consultable** (imagen actual + histórico).
- Métricas por dominio y por estado disponibles en endpoint dedicado.
- Cambios en un dominio **no requieren desplegar los demás**.
- Cobertura:
  - `domain`: 100% unit sobre validaciones.
  - Integración end-to-end por feature con infraestructura real
    (broker, BD, SAP mock).

## 10. Fuentes de información

- Especificaciones de feature: `docs/features/*.md` (migradas desde el proyecto
  Python de referencia).
- Glosario de negocio: `docs/starter/glossary.md`.
- Modelo de dominio: `docs/architecture/domain_model.md`.
