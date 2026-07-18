# SAP Integration (Java)

Integración de datos maestros (customer, article, supplier) desde sistemas
legacy hacia **SAP S/4 Public Cloud**. Migración del POC Python a
**Java 25 + Spring Boot 4.0 + Maven**.

## Documentación

- [`docs/specs/SPEC.md`](docs/specs/SPEC.md) — especificación funcional agnóstica a tecnología: objetivo, dominios, fuentes de entrada, destinos SAP, máquina de estados, criterios de aceptación.
- [`docs/specs/TECH.md`](docs/specs/TECH.md) — stack tecnológico: Java 25 + Spring Boot 4.0 + Maven, puertos y adaptadores, persistencia, observabilidad, testing.
- [`docs/architecture/OVERVIEW.md`](docs/architecture/OVERVIEW.md) — mapas y esquemas del aplicativo: módulos, aggregate Customer, flujos CDC/REST/feature, puertos y adaptadores, máquina de estados, deployment, convención de paquetes.
- [`docs/integrations/SAP_CLOUD_SDK.md`](docs/integrations/SAP_CLOUD_SDK.md) — guía de integración con SAP Cloud SDK: OData VDM (Business Partner), OpenAPI (APIs propias de SAP y callbacks), BTP destinations, arquitectura hexagonal, módulos Maven.
- [`docs/testing/TESTING.md`](docs/testing/TESTING.md) — estrategia y catálogo de la suite de tests (191 tests, tipos, convenciones, contratos SAP, issues conocidos).
- [`docs/GLOSSARY.md`](docs/GLOSSARY.md) — glosario de términos del proyecto con definiciones y enlaces.

## Arquitectura

Reactor Maven multi-módulo. **Un artefacto desplegable por dominio** +
shared kernel `common`. Diagrama completo en
[`docs/architecture/OVERVIEW.md`](docs/architecture/OVERVIEW.md).

```
sap-integration-java/
├── common/      # shared kernel (jar): dominio base, clientes SAP, observabilidad
├── customer/    # customer-app (Spring Boot jar)
├── article/     # article-app
├── supplier/    # supplier-app (futuro)
└── it/          # pruebas de integración cross-dominio + contrato SAP
```

Cada dominio sigue capas por paquete:
`domain` (puro) → `application` (use cases) → `adapters` (infra) → `bootstrap` (Spring wiring).

## Requisitos

- **JDK 23 LTS mínimo** (Java 25 LTS es el objetivo final).
  - El reactor compila con el JDK que tengas en `JAVA_HOME`.
  - Con JDK 23: compila con `<release>23` (por defecto).
  - Con JDK 25: el profile `jdk25` se activa automáticamente y sube el release a 25.
- Maven 3.9+ (o usar el wrapper incluido).
- Docker (para Testcontainers en tests de integración).

## Comandos

```bash
mvn validate                              # validar reactor
mvn -pl common install -DskipTests        # instalar shared kernel local
mvn -pl customer package                  # empaquetar SOLO customer (jar ejecutable)
mvn -pl customer spring-boot:run          # arrancar customer en :8081
mvn compile                               # compilar todos los módulos
mvn test                                  # tests unitarios de todos los módulos
mvn verify                                # unit + slice + integración (con Testcontainers)
mvn -pl it verify                         # solo pruebas de integración cross-dominio
```

### Compilar con un JDK distinto

El parent fija por defecto `<maven.compiler.release>23</m.compiler.release>`.
Si tienes JDK 25 en `JAVA_HOME`, el profile `jdk25` se activa solo y sube a 25.
Para forzar un release concreto sin tocar POMs:

```bash
mvn compile -Dmaven.compiler.release=21    # con JDK 21
mvn compile -Dmaven.compiler.release=25     # forzar JDK 25 (requiere tenerlo)
```

## Arrancar en local (per-dominio)

Customer (cliente):
```bash
mvn -pl customer -am spring-boot:run
# API: http://localhost:8081/customers
# Actuator: http://localhost:8081/actuator/health
# Prometheus: http://localhost:8081/actuator/prometheus
```

Article (artículo):
```bash
mvn -pl article -am spring-boot:run
# API: http://localhost:8082/articles
```

> Requiere infraestructura levantada. Ver [`external-services/README.md`](external-services/README.md):
> Kafka (topics `outbox.CUSTOMER`, `outbox.ARTICLE`), SQL Server, PostgreSQL,
> MongoDB, Elasticsearch/Kibana y MinIO (S3).
>
> ```bash
> cd external-services
> docker compose up -d
> ```

## Debug paso a paso en VS Code

### Opción A — Launch Spring Boot (recomendado)

1. Instala la extensión **Extension Pack for Java** (Microsoft).
2. Crea `.vscode/launch.json` (se incluye plantilla más abajo).
3. `Ctrl+F5` (Run) o `F5` (Debug) sobre la configuración del dominio.

### Opción B — Depurar Maven en línea de comandos

Inicia la app en modo suspendido y conéctate desde VS Code:

```bash
mvn -pl customer -am spring-boot:run \
  -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

En VS Code: `Run and Debug` → crear `launch.json` → tipo `Java` →
`Remote on Port 5005`.

### Opción C — Test unitarios / IT con debugger

Para debuggear un test concreto:
- Abre el fichero de test en VS Code.
- Botón `Debug` (el triángulo con bug) sobre la clase o el método `@Test`.

Para IT que usan Testcontainers (necesitan Docker daemon):
```bash
mvn -pl customer verify -Dtest=CustomerValidationsTest -Dmaven.compiler.release=23
```

### `.vscode/launch.json` (plantilla incluida en el repo)

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Debug Customer App",
      "request": "launch",
      "mainClass": "com.poc.sap.customer.bootstrap.CustomerApplication",
      "projectName": "customer",
      "args": "--spring.profiles.active=dev"
    },
    {
      "type": "java",
      "name": "Debug Article App",
      "request": "launch",
      "mainClass": "com.poc.sap.article.bootstrap.ArticleApplication",
      "projectName": "article",
      "args": "--spring.profiles.active=dev"
    },
    {
      "type": "java",
      "name": "Attach to Remote Customer (port 5005)",
      "request": "attach",
      "hostName": "localhost",
      "port": 5005,
      "projectName": "customer"
    }
  ]
}
```

### `.vscode/settings.json` (opcional)

Asegura que VS Code usa el JDK correcto para la importación del proyecto:

```json
{
  "java.configuration.runtimes": [
    { "name": "JavaSE-25", "path": "C:/Program Files/Java/jdk-25", "default": true },
    { "name": "JavaSE-23", "path": "C:/Program Files/Java/jdk-23" }
  ],
  "java.import.maven.enabled": true,
  "java.format.settings.url": "https://raw.githubusercontent.com/spring-io/spring-javaformat/main/spring-javaformat/eclipse/spring-javaformat.xml"
}
```

## Perfil dev y secrets locales

Por perfil `dev` se cargan `application-dev.yml` (cada dominio) o variables de
entorno. **Nunca commitear secretos**. Copia `application-dev.yml.example` a
`application-dev.yml` y ajústalo. En `application.yml` ya hay placeholders
${...} para todo lo sensible.

## Testing

Suite de **191 tests** (unit, slice web, contract SAP, integration con Testcontainers).

```bash
mvn test                              # unit tests de todos los módulos
mvn -pl customer test                 # solo customer
mvn -pl it verify                     # contratos SAP (WireMock, sin Docker)
mvn -pl it verify -Ddocker.available=true   # + Testcontainers (Kafka, Mongo)
mvn clean verify                      # suite completa
```

Catálogo completo, convenciones, gaps y issues en
[`docs/testing/TESTING.md`](docs/testing/TESTING.md).