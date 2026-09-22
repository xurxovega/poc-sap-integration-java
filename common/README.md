# common — shared kernel

Jar librería, sin aplicación desplegable. Lo importan `customer`,
`article`, `supplier` y `it` (`<dependency>` en cada `pom.xml`).

## Qué hay aquí

- **Máquina de estados** de sincronización: `common/domain/SyncStateMachine.java`
  ([spec](../docs/sdd/common/maquina-de-estados.md)).
- **Cliente SAP** HTTP low-level + Resilience4j + CSRF OData V2:
  `common/sap/RestClientSapClient.java` ([ADR-0001](../docs/architecture/adr/0001-transporte-http-sap-restclient.md),
  [spec](../docs/sdd/common/resiliencia-cliente-sap.md)).
- **Auth SAP** OAuth2 con `OAuth2TokenClient`, `BtpAuthProvider`,
  `S4NativeAuthProvider` ([spec](../docs/sdd/common/autenticacion-sap.md)).
- **Seguridad de las APIs REST** con Keycloak JWT + `@PreAuthorize`:
  `common/security/ApiSecurityConfig.java` ([spec](../docs/sdd/common/seguridad-api.md)).
- **Observabilidad**: `MetricsPort`, `SyncMetrics`,
  `SapResilienceMetrics`, `RetryBudgetGuard` ([spec](../docs/sdd/common/observabilidad.md)).
- **Mappers JSON** OData: `common/sap/json/SapJsonMapper.java`.
- **Persistencia compartida**: `MongoSyncStateRepository` (la usan
  `customer` y `article`; clave para el upsert idempotente en
  `sap_keys`).
- **Soporte test**: fakes, fixtures, `WireMockSapContractTest`.

## Reglas que aplica ArchUnit aquí

- `domain/**` no importa Spring, Jackson, Mongo, Kafka, Micrometer ni JPA
  (`DomainPurityTest`).
- `application/**` sin `@Service` (se cablea con `@Bean` en
  `bootstrap/<dominio>/<Dominio>UseCaseConfig`); `ApplicationPurityTest`
  lo vigila.

## Cómo añadir una capacidad transversal

1. Spec primero en `docs/sdd/common/<nombre>.md` desde
   [`_template/feature.md`](../docs/sdd/_template/feature.md).
2. ROJO: test del comportamiento esperado.
3. Implementar de dentro afuera: `domain/port/` → `application/` → `adapters/`.
4. Cerrar ancla: §9 y §10 del spec, fila en `docs/sdd/common/CHANGELOG.md`,
   `python scripts/sdd-registry-check.py --apply`.

## Cobertura

JaCoCo `check` ≥ 75 % de líneas en `**/domain/**` (medido 2026-09-12:
90 %). El umbral solo sube.

## Ver también

- [`docs/sdd/common/`](../docs/sdd/common/) — specs de capacidades transversales.
- [`docs/architecture/OVERVIEW.md`](../docs/architecture/OVERVIEW.md) §2 — cómo
  encaja `common` en el reactor.