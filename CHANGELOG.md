# Changelog

Qué cambia en la aplicación, contado en términos de **negocio**: qué se puede
hacer ahora que antes no, y qué dejaba de funcionar. Sin detalle técnico.

Dónde está lo demás:

| Necesitas | Dónde |
|---|---|
| El **detalle técnico** de cada defecto: causa, dónde estaba, cómo se arregló | [`docs/sdd/README.md`](docs/sdd/README.md) §6 |
| Los cambios de **una feature concreta** | `docs/sdd/<subproyecto>/CHANGELOG.md` |
| Quién pidió una feature, cuándo y en qué estado está | registro `sdd_registry` — [`docs/sdd/README.md`](docs/sdd/README.md) §8 |
| Lo que **aún no** se ha hecho | [`docs/MEJORAS-Y-PROPUESTAS.md`](docs/MEJORAS-Y-PROPUESTAS.md) |

Cada revisión agrupa sus cambios en **Añadido**, **Cambiado**, **Corregido** y
**Pendiente**. Mientras no haya versiones publicadas, las revisiones se
identifican por fecha.

---

## [Sin publicar]

### 2026-09-12 — Lo que guardamos coincide con lo que SAP tiene (Fase 6 de la auditoría)

#### Corregido

- Un cambio que **volvía** a un valor anterior (A → B → A) se descartaba como
  repetido aunque SAP tuviera el valor intermedio. Ahora solo se descarta lo que
  coincide con **el último** envío aceptado.
- La copia local de «lo que SAP tiene» se guardaba **antes** de enviar: si SAP
  rechazaba, quedaba registrado un dato que SAP nunca recibió, y el siguiente
  evento idéntico se daba por sincronizado. Ahora se guarda solo cuando SAP
  acepta.
- Un reenvío tras un fallo de SAP **sobrescribía** la versión anterior en el
  histórico. Ahora cada intento es una versión distinta y el rastro se conserva.
- Tras el salto a Spring Boot 4.1, la **primera escritura en el histórico**
  (Elasticsearch) fallaba por una librería incompatible. Detectado en la
  verificación en vivo y corregido; ninguna prueba automática lo cubría.

#### Pendiente

- Qué hacer cuando SAP acepta unas partes del cliente y rechaza otras
  (compensación entre features): decisión D-2 del plan.

### 2026-09-12 — Operación y métricas (Fase 5 de la auditoría, parcial)

#### Añadido

- Métricas nuevas para operar el sistema: cuánto tarda cada etapa del proceso
  (leer del origen, validar, guardar, enviar), cuánto tarda cada llamada a SAP y
  con qué resultado, y en qué estado está el cortacircuitos hacia SAP. Antes
  solo se contaban entidades por estado.
- Cada aplicación etiqueta sus métricas con su propio nombre; antes las dos se
  mezclaban.
- Los registros de actividad pueden emitirse en formato JSON estándar (ECS)
  para el stack ELK, activándolo por configuración.

#### Cambiado

- **Parada ordenada**: al detener una aplicación se dejan terminar las
  peticiones y los mensajes en curso en vez de cortarlos.
- Se ha ampliado el margen que Kafka concede para procesar un mensaje, y la
  aplicación comprueba al arrancar que los reintentos hacia SAP caben en ese
  margen. Con la configuración anterior, un SAP degradado podía provocar que
  Kafka expulsara al consumidor una y otra vez.

#### Pendiente

- Trazas distribuidas (seguir una operación entre sistemas): a la espera de
  decidir la integración con OpenTelemetry (D-7).

### 2026-09-12 — Sin credenciales, la aplicación no arranca (adelanto de la Fase 4 de la auditoría)

#### Cambiado

- Hasta ahora, si faltaban las credenciales de SAP, la aplicación arrancaba con
  un token falso y fallaba en la primera llamada con un error indistinguible de
  un problema de SAP. Ahora **se niega a arrancar** y dice qué falta. El token
  falso sigue disponible para el SAP simulado, pero hay que pedirlo
  expresamente.
- Las contraseñas de las bases de datos de origen ya no viajan dentro del
  paquete de la aplicación: se aportan desde el entorno. En local las trae el
  fichero de arranque; en test hay que rellenarlas.

### 2026-09-12 — Cliente SAP sobre un transporte más simple (Fase 3.1 y 3.2 de la auditoría)

#### Corregido (datos bancarios y mandatos, Fase 3.2)

- Los **datos bancarios** se enviaban a SAP con el código BIC en un campo que no
  es el suyo y sin el país del banco. Ahora van con el contrato real de SAP.
- Los **mandatos SEPA** se enviaban a una API de SAP que **no existe**. Ahora se
  dan de alta y se revocan en la API oficial de mandatos, identificados por el
  acreedor SEPA de la empresa (nuevo dato de configuración obligatorio).
- La **baja de un mandato** ya no intenta borrarlo: lo cancela, como exige SAP,
  y se conserva el historial de cobros. Sigue sin haber eventos de mandato
  desde el sistema origen: la funcionalidad está lista pero no se dispara.

#### Cambiado

- La pieza que habla por HTTP con SAP se ha reescrito sobre el cliente
  síncrono estándar de Spring. Se comporta igual (reintentos, cortacircuitos,
  token CSRF) y lo prueban las mismas pruebas, pero deja de arrastrar una pila
  reactiva que no se usaba y el SDK de SAP, que tampoco. La decisión y el
  momento de revisarla quedan escritos en el primer registro de decisiones de
  arquitectura del proyecto.

#### Corregido

- Al pedir el token CSRF a SAP se enviaban siempre unas credenciales fijas,
  aunque el sistema estuviera configurado con otro método de autenticación.
- Cualquier rechazo «prohibido» de SAP se interpretaba como un problema de
  token CSRF y se reintentaba; ahora solo se reintenta cuando SAP lo pide.

### 2026-09-12 — Red de seguridad antes de tocar SAP (Fase 2 de la auditoría)

#### Añadido

- **Integración continua**: cada cambio se compila y se prueba automáticamente
  en GitHub, con y sin Docker. Hasta ahora las pruebas solo se lanzaban a mano.
- **Umbral de calidad que rompe la compilación**: si la lógica de negocio pierde
  cobertura de pruebas, o si esa lógica empieza a depender de tecnología
  concreta (Spring, Mongo, Kafka), la compilación falla.
- Pruebas de contrato que ejercitan el **código real** que llama a SAP: hasta
  ahora comprobaban el simulador, no nuestra aplicación. Incluye la **baja**
  (`DELETE`) y el reintento ante un SAP caído.
- Prueba del registro de estado contra una **base de datos Mongo real** (no
  simulada): reenvío tras fallo, dos procesos escribiendo a la vez y convivencia
  con datos antiguos.

#### Corregido

- **Cuatro pruebas que existían pero nunca se ejecutaban** por un error de
  configuración del build (detectado por la auditoría). Ya corren en cada build.
- Una prueba de infraestructura que nunca arrancaba Kafka por un nombre de
  imagen duplicado.
- La cifra de pruebas era distinta en cada documento (211, 240, 264 según el
  fichero). Ahora hay una sola, **270 pruebas declaradas**, y un test falla si un
  documento se queda atrás.
- La generación de modelos SAP fallaba de forma intermitente en Windows.

#### Cambiado

- **Salto de versión**: Spring Boot 4.1.1 y **Java 25 como mínimo** (antes se
  aceptaban Java 21 y 23). Se retira Spring Cloud, que era incompatible con
  Boot 4 y nada lo usaba.
- Todo lo anterior sigue probado contra un **SAP simulado**. La siguiente fase
  cambia el transporte HTTP y valida el contrato contra el tenant SAP de test.

### 2026-09-11 — Auditoría externa y cambio de naturaleza del proyecto

#### Cambiado

- **El proyecto deja de ser una prueba de concepto y pasa a ser la aplicación
  final.** Las decisiones de seguridad, retención de datos, alta disponibilidad y
  despliegue se toman ya con ese criterio. La documentación lo refleja.
- Una auditoría externa del código completo ha identificado 14 defectos
  bloqueantes y unos 36 de atención. El informe y el plan de acción por fases
  están en `docs/auditorias/`; se ejecutan de más crítico a menos.
- La documentación técnica describía nueve componentes que no existían y
  afirmaba tres cosas falsas sobre cómo se activan los adaptadores. Corregido:
  ahora describe solo lo que hay, y lo aspiracional va marcado como tal.

#### Corregido

- **Un cliente cuyo envío a SAP fallara quedaba bloqueado para siempre.** Ahora
  el siguiente cambio lo vuelve a sincronizar, igual que si el proceso se hubiera
  interrumpido a mitad. Es el defecto más grave de la auditoría.
- **La baja de cliente nunca llegaba a ejecutarse**, y de haberlo hecho habría
  enviado un alta vacía. Ahora se comunica a SAP como baja y la ficha local
  queda **bloqueada, no borrada**: el rastro se conserva para auditoría.
- **Un fallo de infraestructura** (base de datos, buscador, red) a mitad de una
  sincronización dejaba el registro colgado. Ahora queda marcado en error y se
  reintenta con el siguiente evento.
- Con **varias instancias** en marcha, dos procesos ya no pueden pisarse el
  estado de un mismo registro: una escritura gana y la otra se detecta.
- Cuando SAP no está disponible y salta la protección de circuito, el envío se
  **reintenta más tarde** en vez de darse por fallido en silencio.
- Los mensajes malformados van directos a la cola de descartes, sin tres
  reintentos inútiles que solo retrasaban su llegada.

#### Pendiente (detectado por la auditoría)

- Cuatro pruebas automáticas existen pero **nunca se ejecutan** por un error de
  configuración del build (Fase 2).
- Todo lo anterior está probado contra un **SAP simulado**. El re-envío tras
  fallo, la recuperación de un registro interrumpido y la baja por CDC se han
  verificado en vivo sobre el entorno local completo.


### 2026-09-10 — Primera verificación del ciclo completo

Primera vez que el sistema se levanta entero y se prueba de punta a punta. Hasta
esta revisión **nada llegaba realmente a SAP** en un entorno limpio: la
sincronización fallaba en varios puntos distintos.

#### Corregido

- **Ninguna sincronización llegaba a SAP.** El histórico de versiones no se podía
  guardar y eso abortaba el proceso entero, de modo que ni clientes ni artículos
  se enviaban. Ahora el ciclo completo termina correctamente.
- **Un cliente solo se podía sincronizar una vez.** El primer envío funcionaba,
  pero cualquier cambio posterior sobre ese mismo cliente se perdía: el mensaje
  acababa en la cola de descartes sin llegar a SAP y sin aviso. Un cliente se
  re-sincroniza ahora tantas veces como cambie **mientras cada envío termine
  bien**; si un envío a SAP falla, el cliente sigue quedando bloqueado (ver
  *Pendiente* del 2026-09-11).
- **Solo se admitía un cliente y un artículo.** El segundo registro de cada tipo
  fallaba al guardarse.
- **La aplicación de artículos no arrancaba.**
- **El estado y la ficha de cada registro no eran consultables donde debían.** Se
  guardaban en un almacén genérico compartido por los dos dominios en vez de en
  el de cada uno, así que buscar el estado de un cliente no devolvía nada.
- **Los cambios en datos de dirección, fiscales, de contacto o bancarios no se
  enviaban.** El envío por bloques de datos del cliente nunca llegaba a
  ejecutarse.

#### Añadido

- **Puesta en marcha completa con un solo comando**, esperando a que cada pieza
  esté lista antes de seguir, y avisando de dónde mirar si algo falla. Al
  terminar indica en qué dirección está cada servicio.
- **Dos modos de arranque**: todo en el equipo del desarrollador, o las
  aplicaciones en local contra los servicios de un servidor de test. La migración
  a test se puede hacer servicio a servicio.
- **Herramientas para consultar el sistema sin tocar la base de datos**:
  colección de Postman lista para importar y guía de acceso a los datos de
  clientes, artículos, histórico y mensajes.
- **Registro de features**: qué se ha pedido, quién, cuándo, en qué estado está y
  cómo ha ido cambiando.
- **Trazabilidad del comportamiento**: cada feature tiene una especificación con
  sus reglas de negocio y sus criterios de aceptación, y cada criterio está
  respaldado por una prueba automática.

#### Cambiado

- **Forma de trabajar**: toda feature se define antes de construirse, y su
  especificación y su código no pueden divergir. Ningún cambio de comportamiento
  se da por terminado sin la prueba que lo respalde.
- **Documentación reorganizada** por para qué sirve: qué debe hacer el sistema,
  cómo está construido, cómo se desarrolla, cómo se arranca y se prueba.
- La documentación de flujos ahora describe **solo lo que existe**; lo propuesto
  se ha separado a un listado de mejoras, para que nadie confunda una idea con
  una funcionalidad disponible.

#### Pendiente

Conocido y no abordado en esta revisión:

- Los **datos de contacto** (email y teléfono) no usan todavía el formato que
  espera SAP, así que llegarían mal contra un entorno real.
- Los **mandatos SEPA** no llegan desde el sistema origen: los datos bancarios
  van incompletos.
- Si el proceso se interrumpe a mitad de un envío, ese registro **queda
  bloqueado** y hoy no hay forma de desbloquearlo salvo intervenir en la base de
  datos.
- Un fallo parcial puede dejar **datos a medias en SAP**: no hay compensación
  entre los distintos bloques de datos de un cliente.
- Las **APIs no tienen autenticación**: no pueden exponerse fuera del entorno de
  desarrollo.
- Todo lo anterior se ha probado contra un **SAP simulado**, no contra un entorno
  real de SAP.
