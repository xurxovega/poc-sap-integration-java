# Agent Guidelines — SAP Integration (Java)

Migración del POC Python (`poc-sap-integration`) a Java 25 + Spring Boot 4.0 + Maven.

## Stack
- **Lenguaje**: Java 23 LTS mínimo (objetivo Java 25 LTS). Records, sealed, virtual threads.
- **Framework**: Spring Boot 4.0.
- **Build**: Maven 3.9+ multi-módulo reactor. Profile `jdk25` auto-activado con JDK 25.
- **Observabilidad**: Micrometer + Prometheus + OpenTelemetry.
- **Testing**: JUnit 5, Mockito, Testcontainers, WireMock, Spring Cloud Contract.

## Arquitectura
- **Hexagonal / puertos y adaptadores** por dominio.
- **Capas por paquete**: `domain` (puro, sin Spring) → `application` (use cases)
  → `adapters` (infra) → `bootstrap` (Spring wiring).
- **Dominios**: `customer`, `article`, `supplier`. Cada uno = app Spring Boot
  desplegable de forma independiente.
- **Shared kernel** `common/`: StateMachine, clientes SAP (BTP + S/4),
  observabilidad, soporte test. Versionado semántico.
- Regla dependencias: `bootstrap → adapters → application → domain`.
  `domain` no depende de nada. `application` solo de `domain`.

## Puertos clave
- `IngestionPort`: CDC (Debezium Kafka) / eventos Kafka directos / REST.
- `SapOutboundPort`: APIs BTP (xsuaa + Destination Service) / APIs nativas S/4.
- `LegacyRepositoryPort`, `ImageStorePort` (Mongo), `HistoryIndexerPort` (ES),
  `SyncStateRepositoryPort`.

## Comandos críticos
- `mvn validate` — validar reactor.
- `mvn -pl common install -DskipTests` — publicar shared kernel local.
- `mvn -pl customer package` — empaquetar solo customer.
- `mvn verify` — unit + slice + integración.
- `mvn -pl it verify` — pruebas cross-dominio + contrato SAP.

## Documentación

Fuentes de verdad del proyecto (consultar antes de cambiar arquitectura o stack):

- `docs/specs/SPEC.md` — especificación funcional (agnóstica a tecnología): objetivo, dominios, ingestas, destinos SAP, máquina de estados, criterios de aceptación.
- `docs/specs/TECH.md` — stack tecnológico: Java 25 + Spring Boot 4.0 + Maven, capas hexagonales, puertos/adaptadores, persistencia, observabilidad, testing.
- `docs/architecture/OVERVIEW.md` — mapas y esquemas: módulos, aggregate Customer, flujos CDC/REST/feature, deployment, convención de paquetes.
- `docs/testing/TESTING.md` — estrategia y catálogo de tests (191 tests, tipos, contratos SAP, issues conocidos).
- `docs/integrations/SAP_CLOUD_SDK.md` — guía de integración con SAP Cloud SDK: OData VDM, OpenAPI, BTP destinations, arquitectura hexagonal, módulos Maven.
- `docs/GLOSSARY.md` — términos del proyecto con definiciones y enlaces internos/externos.
- `external-services/README.md` — cómo levantar la infraestructura local (Kafka, PostgreSQL, SQL Server, MongoDB, Elasticsearch/Kibana, MinIO).
- Proyecto Python de referencia: `../poc-sap-integration`.

## Al modificar código
- **Nuevo dominio**: añadir módulo al reactor + entrada en `<modules>` del parent.
- **Nueva feature**: use case en `<dominio>/application/`.
- **Nuevo puerto**: interfaz en `<dominio>/domain/port/`; adaptador en
  `<dominio>/adapters/`.
- **Cambio en `common`**: bump de versión según semver; ejecutar `it/` antes.
- **Nada de lógica de negocio en `bootstrap` ni `adapters`**.
- **Secretos fuera del código**: vía variables de entorno / Vault, nunca en YAML
  commiteados.
