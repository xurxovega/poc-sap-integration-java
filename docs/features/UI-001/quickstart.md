# Quickstart — UI-001 Dashboard web: vista por entidad y búsqueda

> Arranque aislado para entender y verificar UI-001 sin tener que leer
> todo el proyecto. El QUICK_START general está en
> [`docs/QUICK_START.md`](../../QUICK_START.md).

## 1. Lo que necesitas

- JDK 25, Maven 3.9+ (usar `./mvnw`), Docker.
- Keycloak levantado (perfil `--with-observability` del quickstart general,
  o Keycloak corporativo si se va contra test/prod).

## 2. Lo que vas a ver

- Un nuevo puerto `8091` sirviendo una web estática con buscador.
- Pestaña "Histórico" con versiones enviadas a SAP.
- Búsqueda por NIF y por id de cliente.

## 3. Pasos

### a) Compilar el módulo nuevo

```bash
cd ../..                              # raíz del repo
./mvnw -pl dashboard-customer -am package -DskipTests
```

### b) Arrancar `external-services/` y `customer-app`

```bash
./scripts/start-all.sh --with-observability   # con Keycloak local
```

### c) Arrancar el dashboard

```bash
./mvnw -pl dashboard-customer spring-boot:run
# http://localhost:8091
```

### d) Verificar las rutas

- `GET /` → landing con buscador.
- `GET /customers/CUST-001` → vista con cabecera + tabs.
- `GET /customers/search?q=A12345678` → busca por NIF en el histórico ES.

### e) PII enmascarada para `sap-external-read`

Pedir un token con ese rol y comprobar que el IBAN del snapshot viene
como `********************3000` y el campo `masked` es `true`.

## 4. Cómo deshacer / parar

```bash
# Detener el dashboard (Ctrl+C en su terminal)
# O el script general:
./scripts/stop-all.sh
```

## 5. Si algo falla

- **401 del dashboard**: ¿`KEYCLOAK_ISSUER_URI` apunta al Keycloak local?
  ¿el cliente `dashboard-customer` existe en el realm?
- **Vista vacía**: ¿hay histórico en ES para `CUST-001`? Provocar primero
  un `POST /customers/sync`.
- **Búsqueda por NIF no devuelve**: el histórico ES ya lleva `fiscal.taxId`
  pero hay que indexarlo. Revisar el spec de `sincronizacion-cliente.md`.