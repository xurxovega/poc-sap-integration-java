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
  re-sincroniza ahora tantas veces como cambie.
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
