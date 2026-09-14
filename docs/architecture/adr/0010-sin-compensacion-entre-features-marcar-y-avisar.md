# ADR-0010 — Sin compensación entre features: marcar el ciclo como error, dejar rastro por parte y avisar

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-14 |
| **Decisión del plan** | D-2; decisión del usuario del 2026-09-14 |
| **Reevaluar cuando** | SAP ofrezca una operación atómica para el agregado (deep insert/`$batch` con changeset único, Fase 3) que haga innecesario el envío por partes, o negocio exija que un cliente nunca quede a medias en SAP ni un instante |

## 1. Contexto

Un cliente se envía a SAP en **cuatro llamadas** (dirección, fiscal, contacto,
banco), cada una sobre su propia línea de estado. Si la tercera falla, SAP queda
con dos partes nuevas y dos viejas. La auditoría (A3) lo señaló como la brecha
estructural más grande. Había que decidir qué hacer con ese estado intermedio.

## 2. Opciones

| | Sin compensación (marcar y avisar) | Saga: deshacer lo que entró | Spring Modulith / eventos internos |
|---|---|---|---|
| Qué queda en SAP tras el fallo | las partes que entraron | lo anterior, tras **dos** escrituras más (deshacer = otra modificación) | igual que saga |
| Rastro en SAP | una modificación OK y una KO por parte | además, la vuelta atrás: tres movimientos por parte, auditoría confusa | igual |
| Coste de la vuelta atrás | ninguno | leer el estado anterior de cada parte y reenviarlo; puede fallar a su vez | más infraestructura |
| Cómo se resuelve | el siguiente evento reenvía; con upsert idempotente, reenviar lo que ya está es inocuo | automático pero frágil | automático pero frágil |
| Quién se entera | alerta + estado por parte consultable | nadie, si la saga «funciona» | nadie |

## 3. Decisión

**No compensar.** Como no hay transaccionalidad en SAP, deshacer sería otra
modificación: dejaría en SAP la traza de una modificación buena, una mala y
una vuelta atrás, y podría fallar igual. En su lugar:

1. El agregado termina en `SAP_ERROR` (o `INVALID`) — **se marca como error**.
2. Cada parte conserva su propia línea de estado (`<cliente>:ADDRESS`,
   `:FISCAL`, `:CONTACT`, `:BANKING`) con la última transición, hash e
   instante — **se sabe dónde falló**. `GET /customers/{id}/state` lo devuelve.
3. Se **avisa**: `WARN` estructurado (`ALERTA sincronizacion parcial ... ok=[...]
   fallidas=[...]`) y un mensaje JSON en el topic `sap.sync.alerts`
   (`SyncNotificationPort`), más la métrica `sap_sync_feature_result_total`.
4. El siguiente evento de la entidad reenvía todas las partes; cuando exista el
   upsert idempotente (Fase 3), reenviar una parte que ya está es inocuo.

## 4. Consecuencias

- Entre el fallo y el siguiente evento, SAP tiene un cliente **parcialmente
  actualizado**. Es visible (estado por parte, alerta) y aceptado por negocio
  hasta que la Fase 3 aporte el envío atómico o el upsert.
- Quien consuma `sap.sync.alerts` (correo, ticket, panel) es una pieza de
  operación pendiente; mientras, Loki/Grafana pueden alertar sobre el `WARN`.
- Spec: [`../../sdd/customer/sincronizacion-cliente.md`](../../sdd/customer/sincronizacion-cliente.md) R-9;
  runbook 1 de [`../../operacion/RUNBOOKS.md`](../../operacion/RUNBOOKS.md).
