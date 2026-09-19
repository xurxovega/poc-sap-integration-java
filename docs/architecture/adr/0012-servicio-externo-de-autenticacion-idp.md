# ADR-0012 — Servicio externo de autenticación: Keycloak como IdP; Zitadel y authentik evaluadas

| | |
|---|---|
| **Estado** | 🟡 Propuesta (la decide el propietario del proyecto) |
| **Fecha** | 2026-09-18 |
| **Reevaluar cuando** | el propietario decida sustituir Keycloak, o la empresa cambie de proveedor de identidad corporativo (mismo disparador que [ADR-0007](0007-keycloak-como-proveedor-de-identidad-de-las-apis.md)) |

## 1. Contexto

[ADR-0007](0007-keycloak-como-proveedor-de-identidad-de-las-apis.md) ya decidió Keycloak como proveedor de identidad de las APIs, y está implementado: resource server OAuth2, roles `sap-*`, `@PreAuthorize` por endpoint. La auditoría del 2026-09-18 (hallazgos **2A-1** y **2A-2**) encontró dos huecos que no dependen de qué IdP se use, pero cuya solución sí puede verse afectada por esa elección: sin validación de [`aud`](../../GLOSSARY.md#aud-audiencia) (audiencia), cualquier token del mismo emisor con un rol `sap-*` entra; y `AccessScope` falla en abierto (sirve PII sin enmascarar cuando no hay autenticación). Este ADR no repite esa decisión: evalúa si, al cerrar esos huecos, conviene **mantener** Keycloak o **sustituirlo**, con dos alternativas de mercado analizadas para que la decisión no se tome a ciegas. Detalle completo, requisitos derivados del repositorio y plan de adopción en [`SERVICIO-AUTENTICACION.md`](../../tools-integrations/SERVICIO-AUTENTICACION.md).

## 2. Opciones

| | A — Mantener Keycloak | B — Zitadel | C — authentik |
|---|---|---|---|
| Roles/claims vs. el conversor actual | Encaja sin tocar código (`KeycloakRoleConverter.java:33-38`) | Claim URN propio (`urn:zitadel:iam:org:project:roles`) → conversor nuevo | Moldeable, puede imitar el formato actual (convención frágil) |
| `aud` por cliente | Requiere *Audience mapper* explícito (no va de serie) | De serie: el token nace con audiencia | Vía *property mapping* (sin verificar en detalle) |
| Licencia | Apache 2.0 | AGPLv3 (o acuerdo comercial) | MIT (núcleo) + Enterprise de pago |
| Rotación de secretos | Múltiples clientes, rotación por cliente | Sin verificar en detalle | **Un solo secreto por proveedor a la vez**: rotación con ventana |
| Coste de cambio | Ninguno: ya está operando | Conversor de roles + reabrir ADR-0007 + revisión legal de AGPL | Conversor o convención de claims a documentar |

## 3. Decisión

**Propuesta**: mantener Keycloak. Es el único candidato cuyo formato de roles coincide hoy con lo que la aplicación ya lee, y la empresa ya lo opera — adoptarlo de nuevo costaría configuración, no código. Zitadel es la alternativa a considerar si algún día `aud` de serie pesa más que el coste de reescribir el conversor de roles y reabrir ADR-0007; authentik queda descartada mientras R-I (rotación de secretos sin ventana) sea un requisito, por su límite de un solo secreto por proveedor.

Esta decisión **la toma el propietario del proyecto**, no el equipo de desarrollo: por eso el estado es Propuesta y no Aceptada. Mientras no se acepte, el trabajo de cerrar 2A-1 y 2A-2 (validar `aud`, `AccessScope` fail-closed) es válido para cualquiera de las tres opciones y no debería bloquearse por este ADR.

## 4. Consecuencias

- Si se acepta tal cual: no hay cambio de IdP, solo el trabajo ya identificado en [`SERVICIO-AUTENTICACION.md`](../../tools-integrations/SERVICIO-AUTENTICACION.md) §4 (validar `aud`, `AccessScope` fail-closed, guard de modo abierto), con su spec y AC en `docs/sdd/common/seguridad-api.md`.
- Si se decide cambiar a Zitadel o authentik: se reabre este ADR y ADR-0007, se sustituye `KeycloakRoleConverter` (o se generaliza), se actualiza [`KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md) y se repiten las pruebas contra el Keycloak corporativo que hoy ya están hechas.
- Ninguna de las tres opciones cierra 2A-1 por sí sola: la validación de `aud` es un cambio en la aplicación, con su test en rojo antes que el código, sea cual sea el IdP.
- Relacionado con **2A-1** y **2A-2** (auditoría 2026-09-18 §3.2); no sustituye a ADR-0007, lo complementa.
