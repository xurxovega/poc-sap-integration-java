# ADR-0005 — MySQL para el registro de features SDD

| | |
|---|---|
| **Estado** | ✅ aceptada (retroactiva: documenta una decisión ya tomada al construir el sistema) |
| **Fecha** | 2026-09-12 (decisión original: 2026-09-10) |
| **Reevaluar cuando** | el registro pueda generarse íntegramente desde los specs versionados (T44 del plan) y nadie consulte la base; entonces MySQL sobra |

## 1. Contexto

El método del repo es SDD: cada feature tiene su spec en `docs/sdd/` y un
changelog por carpeta. El usuario quiso además **información ampliada** por
feature (quién la pidió, cuándo, estado) y un **registro de eventos de ciclo de
vida** (alta, modificación, baja) consultable con SQL y con DBeaver, separado de
los almacenes de la aplicación.

## 2. Opciones

| | MySQL (`sdd_registry`) | Fichero versionado (CSV/YAML) | Tabla en Mongo de la app |
|---|---|---|---|
| Consulta ad hoc (DBeaver, SQL) | sí | no | limitada |
| Vive en git | no (solo el seed) | sí | no |
| Se mezcla con datos de la app | no | no | sí |
| Sobrevive a un `--purge` del compose | no (se regenera del seed) | sí | no |

## 3. Decisión

MySQL 8.4 en el compose (`mysql-sdd`, base `sdd_registry`, tablas `feature` y
`feature_evento`), con el seed en `external-services/mysql/init.sql`. La regla
de trabajo exige un evento por cada alta/modificación/baja de spec
([`AGENTS.md`](../../../AGENTS.md) §1.4).

## 4. Consecuencias

- Lo que no vive en git no sobrevive: los eventos registrados a mano se pierden
  con `--purge`. Por eso existe `scripts/sdd-registry-check.py`, que compara los
  specs con la tabla `feature` y puede recrear las filas de features (no los
  eventos) desde los specs.
- El disparador de reevaluación es real: si la comprobación desde los specs
  basta, la base se retira y este ADR se sustituye.
- Spec/índice: [`../../sdd/README.md`](../../sdd/README.md) §8.
