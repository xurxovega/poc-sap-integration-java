# Quickstart — UI-002 Vista grafo del flujo de integración por entidad

> Arranque aislado para entender y verificar UI-002. Asume que UI-001 ya
> está levantada (ver [`docs/features/UI-001/quickstart.md`](../UI-001/quickstart.md)).
> El QUICK_START general está en [`docs/QUICK_START.md`](../../QUICK_START.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.
- UI-001 funcionando (módulo `dashboard-customer` levantado en :8091).

## 2. Lo que vas a ver

- Una pestaña "Grafo" en la vista de cada entidad.
- SVG con los nodos `Legacy → Debezium → Kafka → SyncCustomerUC → features → SAP`.
- Colores según el estado final del último ciclo (verde/ámbar/rojo/gris).

## 3. Pasos

### a) Provocar al menos un sync completo (REST o CDC)

```bash
curl -s -X POST http://localhost:8081/customers/sync \
  -H 'Content-Type: application/json' \
  -d '{"entityId":"CUST-001","operation":"UPDATE"}'
```

### b) Visitar la pestaña Grafo

```
http://localhost:8091/customers/CUST-001/graph
```

Salida esperada: SVG inline con los nodos coloreados por estado. **Sin
JavaScript de cliente** (la vista es HTML + SVG estático).

### c) Verificar accesibilidad

Abrir el HTML generado y revisar con un lector de pantalla: el SVG debe
tener `<title>` y `<desc>` que describan el flujo.

### d) Validar el SVG con `xmllint`

```bash
xmllint --noout <(curl -s http://localhost:8091/customers/CUST-001/graph | grep -o '<svg.*</svg>')
```

## 4. Cómo deshacer / parar

```bash
# Detener el dashboard (Ctrl+C en su terminal)
./scripts/stop-all.sh
```

## 5. Si algo falla

- **Grafo vacío**: no hay `lastCycle` para esa entidad (es `null` en
  ciclos anteriores a la traza). Provocar un sync nuevo y reintentar.
- **Nodos sin aristas**: el `cycleId` no enlaza con las features; revisar
  `FeatureSyncPipeline` y `SyncCycleRecorder` en `common/`.
- **Colores incorrectos**: revisar el mapeo estado→color en el generador
  de SVG (`dashboard-customer/.../application/grafo/`).