# Features del proyecto objetivo

> Cada feature del proyecto `poc-sap-integration-java` se documenta en su
> propia carpeta bajo `docs/features/`, siguiendo la convención del
> repositorio de agentes (`features/<nombre>/README.md`): un README por
> feature, alineado con su spec en `docs/sdd/<sub>/`.
>
> **Diferencia importante**: este `docs/features/` es **del proyecto
> objetivo**, no del repositorio de agentes. El del repositorio de agentes
> vive en `code/agents/features/` y documenta features del propio repo de
> agentes.

## Convención

```text
docs/features/
└── <CODIGO>/                       # p. ej. OBS-005, UI-001, UI-002, OPS-010
    ├── README.md                   # objetivo, estado, alcance, criterios de aceptación
    ├── quickstart.md               # cómo se verifica la feature aislada
    └── feature-execution-graph.html # grafo de la ejecución (al cierre)
```

El `README.md` sigue los campos del repo de agentes:

| Campo | Contenido |
|---|---|
| **Objetivo** | qué problema resuelve esta feature |
| **Estado** | Incorporada / En curso / Pendiente / Cancelada + fecha |
| **Alcance** | qué entra y qué no (con motivo) |
| **Ficheros afectados** | rutas del repo que tocará |
| **Criterios de aceptación** | qué tiene que ser cierto para darla por buena |
| **Validación** | cómo se verifica |

El `quickstart.md` permite verificar la feature sin leer todo el proyecto:
requisitos → qué se ve → pasos (arrancar / provocar / verificar) → parar →
si falla. Estructura fija.

## Catálogo

| Código | Nombre | Estado | Quickstart | Spec |
|---|---|---|---|---|
| OBS-005 | Recolección y consumo de la observabilidad | En curso | [quickstart](OBS-005/quickstart.md) | [docs/sdd/common/observabilidad.md](../sdd/common/observabilidad.md) (enmienda) |
| UI-001 | Dashboard web: vista por entidad y búsqueda | Pendiente | [quickstart](UI-001/quickstart.md) | [docs/sdd/customer/consulta-entidad-ui.md](../sdd/customer/consulta-entidad-ui.md) |
| UI-002 | Vista grafo del flujo de integración por entidad | Pendiente | [quickstart](UI-002/quickstart.md) | [docs/sdd/customer/consulta-entidad-grafo-ui.md](../sdd/customer/consulta-entidad-grafo-ui.md) |
| OPS-010 | Broker de mensajería: Kafka → Redpanda | Pendiente | [quickstart](OPS-010/quickstart.md) | [docs/sdd/common/broker-de-mensajeria.md](../sdd/common/broker-de-mensajeria.md) + [ADR-0014](../architecture/adr/0014-redpanda-como-broker-de-mensajeria.md) |

> Los códigos siguen a las filas ya existentes en
> [`docs/MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md): `OBS-1..4`,
> `OPS-1..9`, `UI-1..7`. Numeramos a continuación de los ya publicados.

## Cómo se actualiza

`docs-writer` mantiene este catálogo en cada cierre de feature:

1. Crear la carpeta `docs/features/<codigo>/` al **arrancar** la feature.
2. Rellenar `README.md` (estado "En curso") y `quickstart.md` (con TBD).
3. Al **cerrar** la feature, generar `feature-execution-graph.html` con la
   skill `architecture-diagram` del repo de agentes, actualizar el estado a
   "Incorporada" con fecha, y enlazar la fila en este índice.
4. Cancelar una feature no borra la carpeta: queda con estado "Cancelada"
   y motivo, por trazabilidad.