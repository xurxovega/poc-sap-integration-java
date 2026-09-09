# <feature-id> — <nombre de la feature>

<!--
Plantilla SDD. Copiar a docs/sdd/<feature-id>/spec.md y rellenar.
Reglas: describe COMPORTAMIENTO OBSERVABLE, no implementación.
Nada de stack aquí: enlaza a ../../architecture/TECH.md.
Todo AC-n debe tener al menos un test que lo cite.
-->

| | |
|---|---|
| **Dominio** | `customer` \| `article` \| `supplier` |
| **Estado** | 🚧 en diseño \| ✅ implementado \| ⚠️ implementado con brechas \| 🔮 futuro |
| **Entradas** | CDC (`outbox.<DOM>`) \| Kafka directo \| REST `POST /<dom>/sync` |
| **Destino SAP** | BTP \| S/4 nativo \| ninguno |
| **Última revisión** | AAAA-MM-DD |

## 1. Objetivo

Una o dos frases: qué problema de negocio resuelve esta feature y para quién.

## 2. Alcance

**Dentro**: …

**Fuera** (y por qué): …

## 3. Entrada

Qué llega y qué se considera válido para *entrar* al flujo.

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| | | | |

Contrato común del mensaje de ingesta: [`../../architecture/TECH.md`](../../architecture/TECH.md) §6.

## 4. Reglas de negocio

Numeradas y verificables. Una regla = una frase con un veredicto claro.

| # | Regla | Resultado si no se cumple |
|---|---|---|
| R-1 | | `INVALID` / `ERROR` / … |

## 5. Salida

Qué se produce: payload hacia SAP (entidad y campos), imagen persistida,
documento indexado. Mapeo campo a campo si aplica.

| Campo origen | Campo SAP | Transformación |
|---|---|---|
| | | |

API SAP consumida: ver [`../sap-api-catalog.md`](../sap-api-catalog.md).

## 6. Estados y errores

Recorrido esperado por la máquina de estados
([`../../architecture/OVERVIEW.md`](../../architecture/OVERVIEW.md) §5) y qué
lleva a cada estado de error.

| Situación | Estado final | Reintentable |
|---|---|---|
| | | |

## 7. Criterios de aceptación

Cada AC es un test. Redactar en formato *dado / cuando / entonces*.

| AC | Criterio | Test |
|---|---|---|
| AC-1 | Dado …, cuando …, entonces … | `…Test#…` |
| AC-2 | | |

Aplican además los [criterios globales](../README.md#4-criterios-de-aceptación-globales).

## 8. Observabilidad

Métricas, logs y trazas que esta feature debe emitir para ser diagnosticable.

## 9. Trazabilidad spec ↔ código

Se actualiza en el mismo PR que el código.

| Elemento del spec | Código | Test |
|---|---|---|
| R-1 | `…Validations.java` | `…Test` |
| §5 mapeo | `…Adapter.java` | `…AdapterTest` |

## 10. Cambios

| Fecha | Cambio | PR |
|---|---|---|
| AAAA-MM-DD | Spec inicial | |
