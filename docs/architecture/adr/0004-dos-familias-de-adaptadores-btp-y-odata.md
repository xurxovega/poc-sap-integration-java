# ADR-0004 — Dos familias de adaptadores hacia SAP: BTP y OData nativo

| | |
|---|---|
| **Estado** | ✅ aceptada (retroactiva: documenta una decisión ya tomada al construir el sistema) |
| **Fecha** | 2026-09-12 (decisión original: julio de 2026) |
| **Reevaluar cuando** | exista (o se descarte definitivamente) el servicio BTP intermedio; o la Fase 3 concluya que el upsert OData es la única ruta viable, en cuyo caso la familia BTP se retira |

## 1. Contexto

Al arrancar no estaba decidido si la plataforma hablaría **directamente** con
las APIs OData de S/4 Public Cloud (`API_BUSINESS_PARTNER`,
`API_APAR_SEPA_MANDATE_SRV`, `API_PRODUCT`) o con un **servicio intermedio en
BTP** (CAP/Integration Suite) con un contrato propio y más simple. El equipo SAP
no había construido ese servicio y el tenant de test no estaba disponible.

## 2. Opciones

| | Solo OData nativo | Solo BTP intermedio | Ambas detrás del mismo puerto |
|---|---|---|---|
| Dependencia externa | ninguna nueva | un servicio BTP que no existe | ninguna para arrancar |
| Contrato | el oficial de SAP, complejo (CSRF, deep insert, `If-Match`) | el nuestro, simple | los dos |
| Coste de cambio de opinión | reescribir adaptadores | reescribir adaptadores | una propiedad por feature |
| Duplicación | no | no | dos adaptadores por feature |

## 3. Decisión

Un **puerto por feature** (`AddressSapPort`, `FiscalSapPort`, ...) con dos
implementaciones excluyentes elegidas por configuración
(`sap.odata.<feature>.enabled`): `Btp*Adapter` (destino `BTP`, contrato propio
`/sap/btp/odata/*`, activo por defecto) y `BusinessPartner*ODataAdapter` /
`SepaMandateODataAdapter` (destino `S4_NATIVE`, modelos generados de las specs
oficiales). Ambas familias comparten `SapClient` (ADR-0001) y la máquina de
estados.

## 4. Consecuencias

- Se puede migrar feature a feature de BTP a OData con una variable.
- El precio es duplicación (~25 % del código de `customer`, auditoría A5) y
  el riesgo de que la familia BTP apunte a endpoints que nadie implementa: los
  paths `/sap/btp/odata/*` son un **contrato propuesto**, no un servicio real.
- La familia OData es la que se valida contra el tenant de test (Fase 3); si el
  servicio BTP no llega, este ADR se sustituye por uno que retire `Btp*`.
- La Fase 7 del plan (refactor `SyncPipeline<E>`) reduce la duplicación sin
  cambiar esta decisión.

## 5. Decisión D-10 (2026-09-18) — la familia BTP se mantiene

La auditoría de verificación del 2026-09-18 (hallazgo **2B-4**) confirmó que
la familia `Btp*Adapter` sigue siendo la **activa por defecto**
(`sap.odata.<feature>.enabled=false` salvo que se diga lo contrario) y sigue
apuntando a un contrato (`/sap/btp/odata/*`) sin servicio real detrás. El
propietario del proyecto decide **mantener** la familia BTP, no retirarla:
es una vía real que SAP ofrece y que el propietario **ya ha probado, aislada,
en su propio espacio BTP**. El estado exacto, sin ambigüedad, es el que
recoge [`INTEGRATION-PATTERNS.md`](../INTEGRATION-PATTERNS.md) ("Las tres
vías con SAP"): *el servicio BTP funciona por sí solo y se ha probado en el
espacio del propietario, pero falta integrarlo con esta aplicación: ni SAP
llamando a nuestro servicio ni nuestro servicio llamando a SAP a través de
BTP se ha probado extremo a extremo*.

**Lo que esta decisión NO resuelve**: el riesgo de que un despliegue con la
configuración por defecto del `ConfigMap` (`deploy/k8s/base/common.yaml`)
escriba contra un servicio que nadie ha integrado todavía queda como **riesgo
abierto 2B-4**, con una mitigación propuesta (no implementada): un guard de
arranque que exija `sap.btp.base-url` configurada y no apuntando a `localhost`
cuando la familia BTP esté activa para alguna feature, igual que hoy existe
un guard equivalente para las credenciales SAP (`sap.auth.allow-stub`). Ver
la propuesta en [`MEJORAS-Y-PROPUESTAS.md`](../../MEJORAS-Y-PROPUESTAS.md).

**Reevaluar cuando**: se complete la integración extremo a extremo con el
servicio BTP real (en cuyo caso el contrato de este ADR pasa de "propuesto" a
"real" y se documenta en el catálogo de contratos SAP), o el propietario
decida lo contrario.
