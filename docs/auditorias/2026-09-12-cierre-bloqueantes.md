# Cierre de bloqueantes de la auditoría del 2026-09-10 — estado a 2026-09-12

Bloqueante a bloqueante, cerrado o abierto, con la evidencia. Es la «auditoría
de cierre» de la Fase 9 del [plan](2026-09-10-plan-de-accion.md), hecha por la
misma sesión que ejecutó las fases: **pendiente de verificación por una sesión
distinta**, como exige el propio plan.

| # | Hallazgo | Estado | Evidencia |
|---|---|---|---|
| B1 | `SAP_ERROR` y estados intermedios eran sumideros | ✅ cerrado en diseño, **parcial en cobertura** (matiz de la verificación del 2026-09-18) | `beginCycle` desde cualquier estado + test de propiedad; verificado en vivo (re-sync tras 500, entidad atascada). `84a9da4`. Spec `common/maquina-de-estados.md`. El test de re-sync desde `SAP_ERROR` con la máquina real solo existe en `customer`; `article` sigue con el puerto de estado mockeado (auditoría 2026-09-18, hallazgo 2A-13) |
| B2 | DELETE ilegal y `POST {}` | ✅ cerrado | `SENDING_SAP` estado de entrada; `DELETE` HTTP real; imagen bloqueada; CDC DELETE verificado en vivo. `84a9da4`. Spec `customer/baja-cliente.md`. Queda validar en el tenant qué operación es la baja en S/4 |
| B3 | Ruta OData sin upsert; contacto vacío; BIC en `BankIdentification`; mandatos a API inexistente | 🚧 parcial | Banco y mandato corregidos (`e012376`, specs de datos bancarios y baja de mandato). **Abierto**: upsert con `AddressID` y contacto por `A_AddressEmailAddress`, bloqueados por la comprobación contra el tenant (`CHECKLIST-TENANT-SAP.md`, PRD-11, PRD-2) |
| B4 | APIs REST y actuator sin autenticación; PII expuesta | ✅ cerrado (pendiente de probar contra el Keycloak corporativo) | Resource server JWT de Keycloak, roles `sap-*` con jerarquía, `@PreAuthorize` obligatorio por ArchUnit, PII enmascarada para `external-read`, actuator solo admin salvo health/info/prometheus. Spec `common/seguridad-api.md`, ADR-0007. A8 en `b4a8736` |
| B5 | Credencial de tenant en el repo anidado | ✅ mitigado por decisión del usuario | `sap-sdk-client/` en `.gitignore`, sin remoto; **no rotada**. Riesgo residual documentado en el plan |
| B6 | Contract tests sin código de producción | ✅ cerrado | Adaptadores reales sobre `RestClientSapClient` contra WireMock, failsafe. `299567a` |
| B7 | 4 tests que nunca corrían | ✅ cerrado | `SyncCustomerControllerTest`, surefire/failsafe fijados, CI. `299567a` |
| B8 | `AGENTS.md` afirmaba «sin `@ConditionalOnProperty`» | ✅ cerrado | Fase 0, `00bbbae` |
| B9 | `TECH.md` describía 9 adaptadores inexistentes | ✅ cerrado | Fase 0, `00bbbae`; TECH revisado en cada fase |
| B10 | Criterio de fin del PoC inexistente | ✅ reformulado | El proyecto es la aplicación final: [`../operacion/APTITUD-PRODUCCION.md`](../operacion/APTITUD-PRODUCCION.md) con las decisiones de negocio marcadas |
| B11 | Estado no determinista (orden por `timestamp`, read-then-write) | ✅ cerrado | `seq` monótona + índice único parcial + `ConcurrentTransitionException`; `SyncStateMongoIT` contra Mongo real. `84a9da4`, `299567a` |
| B12 | Fallo tras `VALID` dejaba la entidad colgada | ✅ cerrado para el fallo tras `VALID`, **matiz: el `try` empieza en `VALID`** (verificación del 2026-09-18) | `try/catch → ERROR` en ambos orquestadores. `84a9da4`. Un fallo entre `RECEIVED` y `VALID` sigue dejando la entidad en `FETCHING`/`VALIDATING` sin pasar a `ERROR`; sin test en `article` (auditoría 2026-09-18, hallazgo B12 parcial, plan C2-4.2) |
| B13 | Circuito abierto tragado como `SapResponse(0)` | ✅ cerrado | `SapCircuitOpenException`, CB por fuera del retry. `84a9da4`; spec `common/resiliencia-cliente-sap.md` AC-4 |
| B14 | Tercera recurrencia del fingerprint `sync-state:reentrada-no-permitida` | ✅ cerrado | Diseño de raíz (B1) + índice de fingerprints en [`../incidencias/README.md`](../incidencias/README.md) con regla «a la segunda recurrencia, tarea de diseño» |

**Resumen**: 12 cerrados, 1 mitigado por decisión (B5), 1 parcial bloqueado por
el tenant (B3).

## Fases del plan

| Fase | Estado | Commits |
|---|---|---|
| 0 Higiene | ✅ | `b55f889`, `00bbbae` |
| 1 Inservible | ✅ verificada en vivo | `84a9da4` |
| 2 Red de seguridad | ✅ | `299567a` |
| 3 SAP real | 🚧 3.1 y 3.2 hechas; resto bloqueado por el tenant | `c07adb0`, `e012376` |
| 4 Seguridad | ✅ A8 + B4 (Keycloak) | `b4a8736`, commit de la Fase 4 |
| 5 Observabilidad | ✅ (trazas apagadas hasta tener Tempo, ADR-0009) | `f6d0af4` |
| 6 Consistencia | ✅ verificada en vivo; D-2 decidida (ADR-0010) | `2c532a7`, `9e7fd6c` |
| 7 Refactor | ✅ hecha (matiz: queda parte de la deduplicación de A5, ver DX-8) | `d9ffce1` (A4, A18, A19), `27fbbfc` (A5/A20 parcial) |
| 8 Supply chain | ✅ + despliegue k8s (ADR-0008) | `16b0336` |
| 9 Documentación y cierre | 🚧 ADRs, incidencias, runbooks, aptitud, checklist del tenant, registro desde specs hechos; quedan las decisiones de negocio | este documento |

## Decisiones que siguen abiertas

D-3 Debezium Server/SMT (D-2 decidida el 2026-09-14: sin compensación, ADR-0010) · D-4 OData V4 · alcance de `supplier` · retención, SLOs,
RTO/RPO, gestión de secretos en el clúster y quién opera
([`../operacion/APTITUD-PRODUCCION.md`](../operacion/APTITUD-PRODUCCION.md)).
