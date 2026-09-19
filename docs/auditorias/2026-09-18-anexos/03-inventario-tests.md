# Inventario suite de tests — poc-sap-integration-java

**Fecha de medición:** 2026-09-18. **HEAD esperado:** ffd86e7 (no verificado con `git log` porque el
modo es solo lectura sobre ficheros del repo objetivo, no se ha comprobado el hash real — dato **no
contado**, tomarlo con reserva).
**Método:** solo lectura — `grep`/`find`/`python3` de conteo, sin `mvn`/`docker`. No se ha ejecutado la
suite: cualquier afirmación sobre "verde/rojo" real está fuera de este informe.

## 1. Recuento de `@Test`

Comando:
```
grep -rE '^\s*@Test(\s|\(|$)' --include="*.java" \
  common/src/test customer/src/test article/src/test it/src/test supplier/src/test | wc -l
```

| Módulo | `@Test` |
|---|---|
| common | 103 |
| customer | 150 |
| article | 45 |
| it | 16 |
| supplier | 0 |
| **Total** | **314** |

`@ParameterizedTest`: 0. `@RepeatedTest`: 0. `@ArchTest`: 7 (ArchUnit, no cuentan como `@Test`).

**Comparación con lo declarado:** `docs/testing/TESTING.md` §1 dice **314 tests** (medido el
12-09-2026), no 313 como asumía el `supuestos` de este encargo — la cifra de 313 no aparece en ningún
sitio del repo actual; probablemente es un desfase de memoria de un ciclo anterior. **314 = 314: la
cifra declarada coincide exactamente con lo contado.** Esto está garantizado por diseño: el propio
repo tiene `it/src/test/java/com/poc/sap/it/TestCountMatchesDocsTest.java`, que aplica el mismo regex
(`(?m)^\s*@Test\b`) sobre `src/test/java` de todos los módulos (excluyendo `sap-sdk-client`) y compara
contra el patrón `\*\*(\d+) tests\*\*` de `TESTING.md`, fallando el build si difiere. El javadoc de ese
test cita el historial de la auditoría anterior: *"La documentacion decia 211, 240, 245 y 250 tests
según el fichero (auditoría A15)"* — es decir, A15 (incoherencia documental) está **resuelto**: ya no
hay una cifra por fichero, hay una sola cifra vigilada por un test que falla si se desincroniza.

No he verificado si README.md/QUICK_START.md/GUIA-PRUEBAS.md citan también 314 (el test solo vigila
TESTING.md) — **no contado**.

## 2. Tests que nunca corren (nombre fuera de los includes de surefire/failsafe)

Solo `it/pom.xml` personaliza surefire/failsafe:
```xml
<!-- it/pom.xml -->
<surefire>excludes: **/*ContractTest.java, **/*IT.java</surefire>
<failsafe>includes: **/*IT.java, **/*ContractTest.java (execution: integration-test + verify)</failsafe>
```
`common`, `customer`, `article`, `supplier` no tienen configuración propia de surefire/failsafe en su
`pom.xml` (comprobado con `grep -A20 surefire\|failsafe` sobre cada uno): usan los defaults de Maven
(`**/*Test.java`, `**/Test*.java`, `**/*Tests.java`, `**/*TestCase.java`) heredados de `pluginManagement`
del pom raíz (líneas 244-251, solo fija versión `3.5.3`, sin includes/excludes propios).

Comando de verificación de nombres huérfanos:
```
find common customer article it supplier -path "*/src/test/*" -name "*IT.java" -o -name "*ContractTest.java"
# → 0 resultados fuera de it/
```
**No se encontraron clases `*IT` o `*ContractTest` fuera de `it/`.** El hallazgo B7 de la auditoría
anterior (`SyncCustomerControllerIT` en `customer` sin failsafe, 4 tests muertos) **ya no existe**: no
hay ningún fichero con ese nombre en el repo actual (ni siquiera renombrado con rastro — puede haberse
eliminado o reescrito). Tampoco hay clases de test cuyo nombre base no encaje en `*Test`/`*IT` (dos
falsos candidatos, `CustomerFixtures.java` e `InMemoryStateRepo.java`, son fixtures sin `@Test`, no
clases de test).

## 3. Tests gateados por `docker.available`

```
grep -rn "docker.available" --include="*.java" .
```
Dos clases en `it/`: `InfrastructureSmokeIT` y `SyncStateMongoIT`, ambas con
`@EnabledIfSystemProperty(named = "docker.available", matches = "true")`. **Sí se ejecutan en CI**:
`.github/workflows/ci.yml` tiene un job separado `e2e-docker` (`needs: build`) que corre
`./mvnw -pl it -am verify -DskipTests=false -Ddocker.available=true`. El comentario de cabecera del
propio `ci.yml` referencia la auditoría: *"CI mínimo (plan de acción Fase 2, auditoría B7/T12): sin esto,
cuatro tests vivieron 53 días sin ejecutarse y nadie lo supo."* — el hallazgo T12/B7 (falta de CI) está
**resuelto**: hay `build` (unit+slice+ArchUnit+contract+JaCoCo+SBOM, sin Docker) y `e2e-docker` (IT con
Testcontainers) como jobs separados, más un job `image` condicionado a tags `v*`.

## 4. % de métodos de test que citan `AC-n`

Dos mediciones, de más laxa a más estricta:

- Ventana de ±8 líneas alrededor del `@Test` (script Python, cuenta falsos positivos de Javadoc de
  clase compartido entre varios métodos): **72/314 = 22,9 %**.
- Solo bloque de comentario/anotaciones **contiguo inmediatamente encima** del `@Test` (sin línea en
  blanco de por medio) o en las 3 líneas siguientes (nombre de método): **65/314 = 20,7 %**.

Ambas muy por encima del **2 %** (5/245) que citaba A14. Es una mejora real, no solo un cambio de
denominador: el propio código lo declara — `TestCountMatchesDocsTest` no vigila esto, pero
`BtpCustomerContractTest.realAdapterIssuesHttpDeleteOnEntityKey` cita explícitamente *"AC-2
(sdd/customer/baja-cliente.md)"* en su Javadoc, patrón que se repite en varios contract tests.

**Specs con al menos un `AC-n` que ningún test de su módulo cita** (cruce `AC-N` por fichero de spec
contra `grep -rl "AC-N\b" <módulo>/src/test`):

| Spec | AC-n sin test |
|---|---|
| `docs/sdd/common/CHANGELOG.md` | AC-14 |
| `docs/sdd/common/maquina-de-estados.md` | AC-14 |
| `docs/sdd/customer/CHANGELOG.md` | AC-7 |
| `docs/sdd/customer/sincronizacion-cliente.md` | AC-7, AC-9 |
| `docs/sdd/article/sincronizacion-articulo.md` | AC-1, AC-6, AC-7, AC-8 |

`article` es el módulo con más huecos (4 de sus 8 AC sin cita), consistente con que tiene el número de
tests más bajo de los módulos con lógica de negocio (45) y sin `AbstractSapContractTest`-style tests
de dominio propios más allá de `S4ArticleContractTest`.
`docs/sdd/common/autenticacion-sap.md`, `idempotencia-y-dedupe.md`, `observabilidad.md`,
`resiliencia-cliente-sap.md`, `seguridad-api.md` y todos los AC de `customer/baja-cliente.md`,
`baja-mandato-sepa.md`, `sincronizacion-datos-bancarios.md`, `sincronizacion-direccion.md` sí tienen
al menos un test que cita cada AC.

**Aviso de método:** la comparación es por número de AC (`AC-7`, `AC-14`...) cruzado contra el módulo
correspondiente al directorio de la spec (`common/`, `customer/`, `article/`); no distingue si el test
que cita "AC-7" en un fichero realmente corresponde a la misma spec que lo declara — con specs
distintas del mismo módulo compartiendo números de AC, hay riesgo de falso negativo/positivo residual.
No he verificado manualmente cada cita una por una — **contado por regex, no por lectura línea a línea
de las 314**.

## 5. `@Mock`/`@MockitoBean`/`mock(...)` sobre clases concretas

Comando: extracción de tipo tras `@Mock`/`@MockitoBean`/`mock(X.class)` (76 apariciones totales),
resolución de cada tipo único contra su fichero en `src/main` para determinar `interface` vs `class`.

29 tipos únicos mockeados, de los cuales **24 son interfaces** (puertos: `SapClient`,
`SyncStateRepositoryPort`, `MetricsPort`, `*SapPort`, `*JpaRepository`, `*MongoRepository`,
`*HistoryRepository`, `CustomerFeatureSync`, etc. — repos Spring Data son interfaces, no clases, aunque
viven fuera de `domain/port/`) y **5 son clases concretas**: `ArticleHistoryUseCase`,
`CustomerHistoryUseCase`, `DeleteCustomerUseCase`, `SyncArticleUseCase`, `SyncCustomerUseCase` —
9 apariciones de `@Mock` en total sobre esas 5 clases.

**Comparación con los 27 de A20:** caída fuerte, de 27 apariciones sobre clases concretas/infra a
**9 apariciones sobre 5 clases concretas** (todas use cases de aplicación, ningún `@Mock` sobre
`SyncMetrics` — A20 citaba `SyncMetrics ×14` explícitamente y no aparece ni una vez en el recuento
actual). El patrón que persiste (mockear el use case en vez de fake de puerto) es el mismo que señalaba
T32, pero a una escala mucho menor. No he verificado si esto es porque T32 se ejecutó parcialmente o
porque los tests que mockeaban `SyncMetrics`/repos Spring Data se reescribieron con fakes — **no
contado en el histórico de commits**, es una foto del estado actual.

## 6. Qué no cubre ningún test

- **Adaptadores/DTOs sin test propio** (cruce nombre base de fichero en `src/main/**/adapters/**` contra
  `src/test/**/adapters/**`, 40 ficheros de adapters en `common+customer+article`): sin correspondencia
  directa aparecen `BusinessPartner*ODataAdapter` (4), `S4ProductDto`, `BtpAddressDto`/`BtpBankingDto`/
  `BtpContactDto`/`BtpCustomerDto`/`BtpFiscalDto`, `Article*`/`Customer*`Document/Entity/Repository. Ojo:
  varios de estos son DTOs/entidades de mapeo puro cubiertos indirectamente por los contract tests de
  `it/` (que instancian el adaptador real) o por tests de repositorio con otro nombre de fichero — el
  cruce por nombre de fichero da falsos positivos; **no he abierto cada uno de los 40 para confirmar
  cobertura real línea a línea**. Lo que sí puedo afirmar con el método usado: no existe un
  `BusinessPartnerODataAdapterTest` ni equivalente para los 4 adaptadores OData de `BusinessPartner*`.
- **Infraestructura real** (Mongo/Kafka/Elasticsearch) solo se ejercita en `InfrastructureSmokeIT` y
  `SyncStateMongoIT`, ambos gateados por `docker.available` — nadie fuera del job `e2e-docker` de CI la
  toca. El propio pom raíz documenta un incidente real de esto: comentario en `pom.xml` líneas 42-47
  sobre un `ClassNotFoundException` de OpenTelemetry en Elasticsearch visto en producción el
  12-09-2026, *"Ningún test lo cazó (los slices no tocan ES; solo lo habría cazado el e2e con Docker)"*
  — confirmación textual del propio repo de que esta laguna es conocida y real, no una hipótesis mía.
- **Endpoints de seguridad**: sí existen `ApiSecurityTest`/`ApiSecurityDisabledTest` en `customer` y
  `article` — no verificado si cubren todos los endpoints o solo una muestra (**no contado**).
- **Tests de configuración (patrón TEST-2)**: existen `CustomerApplicationContextTest`,
  `ArticleApplicationContextTest`, `CustomerMongoDatabaseConfigTest`, `KafkaErrorHandlingConfigTest` —
  el patrón está aplicado a customer/article/common; no he verificado si falta en `supplier` (0 tests,
  módulo placeholder, no aplica) ni si cubre toda config crítica.

## 7. Contract tests de `it/` (9 ficheros, 458 líneas, `AbstractSapContractTest` como base)

Leído íntegro `BtpCustomerContractTest.java` (70 líneas) como muestra representativa:

- **No es "201 a todo".** Cada test define su propio stub (`post(...)`, `delete(...)`) con su propio
  status, y **verifica** con `sap.verify(...)`: método HTTP exacto, path (`urlPathEqualTo`), cabeceras
  (`Idempotency-Key`, `Authorization: Bearer <token>`), y cuerpo por JSONPath
  (`matchingJsonPath("$.BusinessPartner", ...)`, `"$.Name"`, `"$.Status"`). El segundo test además
  verifica **negativamente** que no se emitió un POST (`sap.verify(0, postRequestedFor(anyUrl()))`) —
  esto es exactamente la corrección que pedía B6 ("hasta la Fase 1 era un POST {}", ahora es DELETE real
  sobre la clave de la entidad, con su propio Javadoc citando `AC-2 (sdd/customer/baja-cliente.md)`).
- Instancia el **adaptador real** (`new BtpCustomerAdapter(sapClient, PATH)`), no un mock — resuelve
  el otro pilar de B6 ("stub WireMock llamado con HttpClient de JDK, sin tocar código de producción").
- **Qué NO valida del contrato SAP real**: WireMock es un doble de SAP basado en lo que el propio equipo
  cree que SAP devuelve — no hay verificación contra un esquema OData/EDMX real de S/4 o BTP, ni contra
  un sandbox SAP. Si SAP cambia un campo, rechaza un valor, o exige una cabecera adicional no prevista
  en el stub, estos tests seguirán en verde sin detectarlo. Tampoco cubren latencia/rate-limiting real,
  ni variantes de error 5xx más allá de las que el propio equipo decidió estubar (no he leído los 8
  ficheros restantes línea a línea — esto es una generalización desde el patrón de
  `AbstractSapContractTest` + la muestra leída; **los otros 8 contract tests no se han leído
  íntegramente**, solo se ha confirmado su longitud y nombre).

## 8. Tabla resumen y qué no verifiqué

| Criterio | Nota (1-10) | Motivo |
|---|---|---|
| Exactitud del recuento declarado (314) | 10 | Coincide exacto y está blindado por `TestCountMatchesDocsTest` |
| Tests que corren de verdad (surefire/failsafe) | 9 | 0 huérfanos encontrados; B7 resuelto |
| Cobertura de CI (incl. Docker-gated) | 8 | Job `e2e-docker` separado corre los gateados; no he visto un run real verde |
| Trazabilidad AC-n | 5 | 20,7 % citan AC, mejora fuerte vs 2 % de A14, pero 80 % sigue sin trazar y 5 specs con huecos concretos |
| Disciplina de mocking (puerto vs concreto) | 7 | 9 apariciones sobre 5 clases concretas, todas use cases; caída fuerte vs 27, pero el patrón no está erradicado |
| Cobertura real (adaptadores/infra/seguridad) | 4 | Huecos confirmados por el propio repo (incidente ES/OTel); adaptadores OData de BusinessPartner sin test dedicado |
| Calidad de contract tests | 7 | La muestra leída verifica método/path/cabeceras/body con adaptador real; el resto no se ha leído íntegro |

**Lo que NO verifiqué** (no ejecuté nada, `mvn`/`docker` prohibidos en este encargo):
- No corrí la suite: **no hay evidencia de que los 314 tests estén en verde hoy**, solo de que están
  declarados y de cómo están gateados/incluidos.
- No confirmé el hash de commit `ffd86e7` contra el estado real del working tree.
- No leí íntegramente los 8 contract tests restantes de `it/`, ni los ~314 métodos uno a uno para AC-n
  (muestreo por regex con ventana de líneas, no lectura manual).
- No verifiqué si README.md/QUICK_START.md/GUIA-PRUEBAS.md citan 314 o una cifra distinta (el test de
  guardia solo vigila `TESTING.md`).
- No abrí los 40 ficheros de adapters uno a uno para confirmar cobertura de línea real (JaCoCo) — solo
  cruce de nombres de fichero, con falsos positivos conocidos (DTOs cubiertos indirectamente).
- No comparé contra el historial de commits qué cambió exactamente entre la auditoría del 2026-09-10 y
  hoy (2026-09-18); las mejoras que reporto (A14, A15, A20, B6, B7) se infieren del estado actual del
  código y de comentarios/Javadoc que citan esas auditorías, no de un `git log` que no se ha ejecutado.
