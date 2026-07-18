# SAP Cloud SDK for Java

> Guía de integración con SAP Cloud SDK para los dominios del proyecto:
> `customer`, `article` y `supplier`.

## Propósito

[SAP Cloud SDK for Java](https://sap.github.io/cloud-sdk/docs/java/getting-started) es el SDK oficial de SAP para conectar aplicaciones Java con **SAP S/4HANA Cloud**, **SAP S/4HANA On-Premise** y **SAP BTP**. No es solo un cliente HTTP: abstrae **conectividad, autenticación, generación de clientes tipados, resiliencia y multitenancy**.

En este proyecto lo usamos para:

1. Generar clientes tipados **OData** para entidades maestras de S/4HANA (p. ej. Business Partner).
2. Generar clientes tipados **OpenAPI** para APIs REST propias de SAP y callbacks.
3. Resolver destinos, autenticación y conectividad desde **SAP BTP**.

## Capacidades usadas

| Capacidad | Caso de uso | Enlace oficial |
|---|---|---|
| **OData VDM generator** | Crear/actualizar Business Partner, Material, Supplier | [Generate OData Client](https://sap.github.io/cloud-sdk/docs/java/features/odata/vdm-generator) |
| **OpenAPI generator** | Consumir APIs REST propias de SAP / callbacks | [Generate OpenAPI Client](https://sap.github.io/cloud-sdk/docs/java/features/rest/generate-rest-client) |
| **Connectivity / Destinations** | Resolver URL y auth desde BTP | [Destination Service](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/destination-service) |
| **Resilience & Caching** | Retry, circuit breaker, cache integrados | [Resilience](https://sap.github.io/cloud-sdk/docs/java/features/resilience) |
| **Multitenancy / Thread Context** | Propagar tenant y usuario en BTP | [Thread Context](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context) |
| **BAPI/RFC** | Invocar BAPIs vía RFC/JCo (futuro) | [BAPI/RFC](https://sap.github.io/cloud-sdk/docs/java/features/bapi-and-rfc/overview) |

## Integraciones concretas

### 1. Business Partner — OData VDM

- **Entidad SAP:** `API_BUSINESS_PARTNER` (u otro servicio OData de S/4HANA según release).
- **Dominio:** `customer`.
- **Generador:** `odata-v4-generator-maven-plugin` o `odata-generator-maven-plugin`.
- **Ubicación:** `customer/src/main/java/com/poc/sap/customer/adapters/sap/generated/vdm/`.
- **Adaptador:** `CloudSdkSapClientAdapter` (o similar) usa el VDM generado y se expone mediante el puerto `SapClient`/`SapOutboundPort`.
- **Flujo:**
  1. El caso de uso recibe un `Customer` de dominio.
  2. El adaptador mapea `Customer` → entidad VDM `BusinessPartner`.
  3. El VDM ejecuta `businessPartnerService.createBusinessPartner(...)`.
  4. El adaptador mapea la respuesta de vuelta al dominio y actualiza el estado.

### 2. APIs REST propias de SAP / Callbacks — OpenAPI

- **Origen:** especificación OpenAPI descargada de [SAP API Business Hub](https://api.sap.com/) o proporcionada por el equipo SAP.
- **Generador:** `openapi-generator-maven-plugin`.
- **HTTP client:** **Apache HttpClient 5** (soportado desde SDK 5.26.0) o Spring RestTemplate.
- **Ubicación:** `customer/src/main/java/com/poc/sap/customer/adapters/sap/generated/openapi/`.
- **Casos:**
  - **Outbound:** consumir APIs REST de extensión de SAP.
  - **Inbound (callback):** exponer endpoints REST que reciben eventos de SAP; el controlador bootstrap traduce el payload a un comando de dominio y lo encola/llama al use case correspondiente.

### 3. BTP Destinations

- Reemplaza los proveedores de autenticación actuales (`BasicAuthProvider`, `OAuth2Provider`).
- **Local:** `DefaultHttpDestination.builder(url).authenticationType(...)` o variable de entorno `destinations`.
- **Cloud Foundry:** BTP Destination Service + XSUAA/IAS.
- El adaptador obtiene el destino por nombre con `DestinationAccessor.getDestination("my-s4-destination")`.

## Arquitectura hexagonal

El SDK debe quedar **aislado en la capa de adaptadores**. El dominio y la aplicación no conocen `com.sap.cloud.sdk`.

```text
customer/
├── domain/                    # Customer, ports puros
├── application/               # use cases
├── adapters/
│   ├── sap/
│   │   ├── generated/         # VDM OData + OpenAPI generados
│   │   │   ├── vdm/
│   │   │   └── openapi/
│   │   └── CloudSdkSapClientAdapter.java   # implementa SapClient
│   └── kafka/
└── bootstrap/                 # @ComponentScan incluye "com.sap.cloud.sdk"
```

## Módulos Maven

Añadir al `dependencyManagement` del parent:

```xml
<dependency>
  <groupId>com.sap.cloud.sdk</groupId>
  <artifactId>sdk-modules-bom</artifactId>
  <version>${sap-cloud-sdk.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Módulos por capa:

| Módulo | Dónde | Para qué |
|---|---|---|
| `sdk-core` | `common` | Funcionalidad base del SDK |
| `connectivity-destination-service` | apps (`customer`, `article`...) | Destinos BTP |
| `odata-v4-core` / `odata-core` | dominios con OData | VDM S/4HANA |
| `openapi-core-apache` | dominios con OpenAPI | Cliente REST generado |

## Decisiones clave

- **BOM:** usar `sdk-modules-bom` en lugar de `sdk-bom` para minimizar conflictos con Spring Boot 4.0.
- **No reemplazar el puerto `SapClient`:** añadir un adaptador SDK detrás del puerto existente.
- **VDM/OpenAPI en `adapters/generated/`:** nunca en `domain`.
- **Resiliencia:** coexistencia con Resilience4j existente; el SDK ya usa Resilience4j internamente.

## Fase 0 — Spike de compatibilidad

> Estado: cambios aplicados en POMs y anotaciones de escaneo; **pendiente de compilación en entorno con Java 25 + Maven 3.9+**.

### Cambios realizados

1. **Parent POM** (`pom.xml`):
   - Añadida propiedad `<sap-cloud-sdk.version>5.32.0</sap-cloud-sdk.version>`.
   - Importado `com.sap.cloud.sdk:sdk-modules-bom` en `<dependencyManagement>` **antes** de `spring-boot-dependencies` para minimizar conflictos con Spring Boot 4.0.

2. **Shared kernel** (`common/pom.xml`):
   - Añadida dependencia `com.sap.cloud.sdk:sdk-core`.

3. **Aplicaciones Spring Boot**:
   - `CustomerApplication` y `ArticleApplication` ahora incluyen `com.sap.cloud.sdk` en `@ComponentScan` y `@ServletComponentScan` para cargar los listeners de contexto del SDK.
   - En Spring Boot 4.0, `@ServletComponentScan` se encuentra en `org.springframework.boot.web.server.servlet.context` (no en `org.springframework.boot.web.servlet` como en Spring Boot 3).

### Validación

Ejecutar en el entorno de desarrollo con JDK 25 y Maven 3.9+:

```bash
# Desde la raíz del reactor
mvn clean compile

# Si compila, ejecutar tests
mvn test

# Verificar que no hay choques de dependencias
mvn dependency:tree -pl common | grep -i "sap.cloud.sdk"
```

### Criterios de éxito del spike

- [x] `mvn clean compile` termina con `BUILD SUCCESS`.
- [x] No hay errores de `jakarta.servlet` ni de carga de beans.
- [x] `mvn test` termina con `BUILD SUCCESS` (no hay conflictos de versiones entre `sdk-modules-bom` y Spring Boot 4.0).
- [x] Los tests unitarios existentes siguen pasando.

### Nota sobre Spring Boot 4.0

La anotación `@ServletComponentScan` se movió de `org.springframework.boot.web.servlet` (Spring Boot 3) a `org.springframework.boot.web.server.servlet.context` (Spring Boot 4.0). Esto afecta a la integración descrita en la documentación oficial del SDK, que aún usa Spring Boot 3 como referencia.

### Riesgos detectados

- **Java 25**: SAP solo certifica Java 17 LTS. Java 25 debería funcionar pero sin soporte oficial.
- **Spring Boot 4.0**: SDK v5 se certifica sobre Spring 6 / Spring Boot 3. Spring Boot 4.0 puede generar incompatibilidades transitivas.
- **Dependencias**: `sdk-modules-bom` reduce el riesgo, pero puede haber choques con Jackson, SLF4J, etc.

### Resultado del spike

El spike ha sido **exitoso**: SAP Cloud SDK v5 (`5.32.0`) es compatible con Java 25 y Spring Boot 4.0 en este reactor, usando `sdk-modules-bom` y la ubicación correcta de `@ServletComponentScan` para Spring Boot 4.0.

## Fase 1 — Destino local de prueba

### Cambios realizados

1. **Configuración de destino local** (`common/sap/cloudsdk/SapCloudSdkLocalDestinationConfig.java`):
   - Bean condicional activado por `sap.cloud-sdk.local-destination.enabled=true`.
   - Registra un destino `local-s4` apuntando a `http://localhost:8080` sin autenticación.
   - Usa `DestinationAccessor.prependDestinationLoader(...)` del SAP Cloud SDK.
   - Resetea el loader en `@PreDestroy` para evitar estado estático entre tests.

2. **Propiedad en `application-common.yml`**:
   ```yaml
   sap:
     cloud-sdk:
       local-destination:
         enabled: ${SAP_CLOUD_SDK_LOCAL_DESTINATION_ENABLED:false}
   ```
   Por defecto desactivado; se puede activar vía variable de entorno.

3. **Test** (`common/sap/cloudsdk/SapCloudSdkLocalDestinationTest.java`):
   - Carga el bean con `@SpringBootTest` y verifica que `DestinationAccessor.getDestination("local-s4")` resuelve correctamente.

### Validación

```bash
mvn -pl common test -Dtest=SapCloudSdkLocalDestinationTest
```

### Resultado esperado

- El test pasa y confirma que el SAP Cloud SDK resuelve destinos locales en el entorno Java 25 + Spring Boot 4.0.

### Siguiente paso

**Fase 2**: implementar `CloudSdkSapClientAdapter` que use el destino local para enviar peticiones HTTP a SAP, manteniendo el puerto `SapClient` existente.

## Enlaces útiles

- [Getting Started — SAP Cloud SDK Java](https://sap.github.io/cloud-sdk/docs/java/getting-started)
- [OpenAPI generator](https://sap.github.io/cloud-sdk/docs/java/features/rest/generate-rest-client)
- [OData VDM generator](https://sap.github.io/cloud-sdk/docs/java/features/odata/vdm-generator)
- [Destination Service](https://sap.github.io/cloud-sdk/docs/java/features/connectivity/destination-service)
- [Resilience](https://sap.github.io/cloud-sdk/docs/java/features/resilience)
- [Thread Context / Multitenancy](https://sap.github.io/cloud-sdk/docs/java/features/multi-tenancy/thread-context)
- [SAP API Business Hub](https://api.sap.com/)
