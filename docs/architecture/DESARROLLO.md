# Guía de desarrollo — SDD + TDD

> Cómo se trabaja en este repositorio. **Qué** debe hacer cada feature está en
> [`../sdd/`](../sdd/README.md); **cómo** está construido el sistema, en
> [`../architecture/`](../architecture/OVERVIEW.md); cómo se prueba una vez
> hecho, en [`../testing/`](../testing/GUIA-PRUEBAS.md).

Dos reglas gobiernan todo el desarrollo:

1. **SDD anchor** — spec y código no divergen nunca ([`../sdd/README.md`](../sdd/README.md) §1).
2. **TDD** — no se escribe código de producción sin un test que falle antes.

## 1. Ciclo por feature

```
  ┌─ 0. spec ────────────────────────────────────────────────────────┐
  │  docs/sdd/<subproyecto>/<feature>.md desde _template/feature.md  │
  │  Criterios de aceptación numerados (AC-1, AC-2, …)               │
  └────────────────────────┬─────────────────────────────────────────┘
                           ▼
  ┌─ 1. AC → tests ────────────────────────────────────────────────┐
  │  Cada AC se traduce a uno o más tests. Todos en ROJO.           │
  └────────────────────────┬─────────────────────────────────────────┘
                           ▼
  ┌─ 2. TDD: red → green → refactor ───────────────────────────────┐
  │  De dentro afuera: domain → application → adapters → bootstrap  │
  └────────────────────────┬─────────────────────────────────────────┘
                           ▼
  ┌─ 3. cerrar ────────────────────────────────────────────────────┐
  │  Tabla §9 del spec (trazabilidad) + §10 (cambios)               │
  │  Estado en docs/sdd/README.md §5                                │
  └──────────────────────────────────────────────────────────────────┘
```

Si el trabajo empieza por el código (bug, refactor, hallazgo), el ciclo es el
mismo pero al revés: se reproduce con un test en rojo, se arregla y **se
actualiza el spec** en el mismo PR.

## 2. TDD: el ciclo

| Fase | Qué se hace | Qué **no** se hace |
|---|---|---|
| 🔴 **Red** | Escribir un test que falle **por la razón correcta**. Ejecutarlo y leer el fallo antes de tocar producción. | Escribirlo después del código «para cubrir». Dar por hecho que falla sin ejecutarlo. |
| 🟢 **Green** | El **mínimo** código que lo pone en verde. Duplicar o dejarlo feo está permitido aquí. | Añadir generalidad, configuración o casos que ningún test pide. |
| 🔧 **Refactor** | Limpiar con la suite en verde: nombres, duplicación, extracción. Re-ejecutar tras cada paso. | Refactorizar en rojo. Cambiar comportamiento (eso es un ciclo nuevo). |

Reglas prácticas:

- **Un ciclo = un comportamiento.** Si el test necesita más de un cambio de
  producción para pasar, el paso es demasiado grande: divídelo.
- **El fallo debe ser legible.** Un test que falla con `NullPointerException`
  cuando debería fallar con «IBAN inválido» no está en rojo por la razón correcta.
- **No se testea el framework.** Getters, mapeos triviales de Spring o la
  serialización de Jackson no llevan test propio.
- **Los tests son código de producción**: mismo nivel de revisión y limpieza.

## 3. Orden de las capas

Siempre de dentro afuera. La regla de dependencias
(`bootstrap → adapters → application → domain`) es también el orden del TDD:

| Capa | Tipo de test | Herramientas | Regla |
|---|---|---|---|
| `domain` | unit puro | JUnit 5 + AssertJ | **Sin Spring, sin mocks.** Aquí viven las reglas de negocio del spec §4 |
| `application` (use cases) | unit | JUnit 5 + Mockito sobre los *ports* | Se mockean los ports, nunca la infraestructura real |
| `adapters` | unit / slice | Mockito, WireMock (SAP) | Se verifica el payload y las cabeceras enviadas, no la lógica de negocio |
| `bootstrap` web | slice | `MockMvcBuilders.standaloneSetup(...)` (Boot 4 eliminó `@WebMvcTest`) | Contrato HTTP: códigos, forma del JSON |
| `bootstrap` kafka | unit directo | listener + stub del use case | Enrutado por tipo de operación |
| cross-dominio | integración | Testcontainers + WireMock en `it/` | Al menos un end-to-end por feature |

Empezar por `domain` obliga a que las reglas del spec queden en código puro y
verificable, sin infraestructura de por medio.

## 4. Bucle rápido

```bash
mvn -pl common test                       # shared kernel
mvn -pl customer test                     # un dominio
mvn -pl customer test -Dtest=AddressValidatorTest   # una clase
mvn -pl customer test -Dtest='AddressValidatorTest#rechaza_iban_invalido'
mvn test                                  # toda la suite unit + slice
mvn verify                                # + integración
mvn -pl it verify                         # cross-dominio + contrato SAP
```

Durante el ciclo rojo-verde usa el `-Dtest=` más estrecho posible; la suite
completa se ejecuta antes de abrir el PR.

## 5. Convenciones de test

- Nombre de clase: `<Clase>Test` (unit/slice), `<Escenario>IT` (integración).
- Nombre de método: **camelCase que describe la regla**, no el método bajo prueba
  — es la convención ya establecida en el repo (`missingCityFails`,
  `invalidCountryFails`, `retriesOn5xxUntilSuccess`). `testValidate2` ❌.
- Un comportamiento por test. Si el test necesita `given`/`when`/`then`
  explícitos, sepáralos con línea en blanco; para asertos de una línea, como los
  de `AddressValidatorTest`, no hace falta ceremonia.
- **Cita el criterio de aceptación** en el Javadoc del test — es la mitad del
  ancla SDD del lado del código:

  ```java
  /**
   * AC-3 (sdd/customer/sincronizacion-direccion.md): una direccion sin pais no se envia a
   * SAP y el registro termina en INVALID.
   */
  @Test
  void missingCountryIsNotSentToSap() { … }
  ```

- Las clases de producción citan la sección de arquitectura que las justifica
  (`(OVERVIEW.md §5)`, `(TECH.md §8)`), como ya hace el código existente.
- El resto de convenciones vigentes (fixtures, mocks, strict stubs, AssertJ,
  slice web) están en [`../testing/TESTING.md`](../testing/TESTING.md) §4 y no se
  duplican aquí.

## 6. Definición de hecho

Un cambio está terminado cuando:

- [ ] El spec de la feature existe y refleja el comportamiento final (§9 y §10 actualizados).
- [ ] Cada AC del spec tiene al menos un test que lo cita.
- [ ] Todos los tests nuevos fueron escritos **antes** que su código.
- [ ] `mvn verify` en verde.
- [ ] El estado en [`../sdd/README.md`](../sdd/README.md) §5 está al día; si se cierra o abre una brecha, el changelog §6 también.
- [ ] Ningún documento nuevo duplica algo que ya esté en `architecture/` o `sdd/`.

## 7. Añadir un dominio nuevo

Para un dominio real (p. ej. `supplier`):

1. Spec primero: `docs/sdd/supplier-sync/spec.md`.
2. Módulo `supplier/` con paquetes `domain/application/adapters/bootstrap`;
   añadirlo a `<modules>` del parent `pom.xml` y declarar dependencia a `common`.
3. **Rojo**: tests de `SupplierValidations` a partir de las reglas del spec §4
   (mismo patrón que `AddressValidatorTest`).
4. Aggregate + ports (`LegacyRepositoryPort`, `ImageStorePort`,
   `HistoryIndexerPort`) + `SyncSupplierUseCase`. Reutilizar `SyncStateMachine` y
   `MongoSyncStateRepository` de `common` — **no duplicar**.
5. Adaptadores (SQL/Mongo/ES/SAP) con sus tests; SAP contra WireMock.
6. `@SpringBootApplication` + `@KafkaListener(outbox.SUPPLIER)` +
   `POST /supplier/sync`, con smoke test de contexto.
7. `application.yml` con `spring.config.import=application-common.yml` y sus
   variables de entorno (`*_SERVER_PORT`, BD, …).
8. DDL legacy en `external-services/.../init.sql`; IT con Testcontainers.

## 8. Añadir una feature a Customer

1. Spec en `docs/sdd/customer/<nombre-descriptivo>.md` (p. ej. `sincronizacion-datos-fiscales.md`).
2. **Rojo**: test del validador con los casos del spec §4.
3. Value object (`record`) + validador en `customer/domain/feature/<feature>/`.
4. Alta del valor en el enum `CustomerFeature`.
5. `<Feat>SapPort` + adaptador (BTP y/o S/4) con su test de payload.
6. `Sync<Feat>UseCase` y registro del dispatch en `SyncCustomerUseCase`.
7. Cerrar el ciclo: trazabilidad en el spec §9 e índice en `../sdd/README.md` §5.
