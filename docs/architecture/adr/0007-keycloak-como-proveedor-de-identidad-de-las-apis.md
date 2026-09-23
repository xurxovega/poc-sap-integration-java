# ADR-0007 — Keycloak (OAuth2 resource server) como identidad de las APIs REST

| | |
|---|---|
| **Estado** | ✅ aceptada |
| **Fecha** | 2026-09-12 |
| **Decisión del plan** | B4 de la auditoría; decisión del usuario del 2026-09-12 |
| **Reevaluar cuando** | la empresa cambie de proveedor de identidad, o haga falta autorización por datos (qué clientes puede ver cada usuario) además de por rol |

## 1. Contexto

Las APIs (`/sync` dispara escrituras facturables en S/4; `/history?full=true`
devuelve IBAN, NIF, email y teléfono) estaban abiertas. La empresa ya tiene
**Keycloak** integrado y quiere: usuarios normales con roles, usuarios de
servicio de lectura y escritura, admin y superadmin, y **clientes externos** con
lectura sin datos sensibles; y poder decir **en cada endpoint** quién puede
llamarlo, como los atributos de .NET.

## 2. Opciones

| | Resource server OAuth2 (JWT de Keycloak) | API keys propias | Sesión + login en la app |
|---|---|---|---|
| Identidad | la corporativa, ya existente | otra más que gestionar | otra más |
| Roles | los de Keycloak, en el token | tabla propia | tabla propia |
| Coste por petición | validar firma, sin llamada | consulta a BD | sesión |
| Servicios (M2M) | client credentials | natural | incómodo |
| Externos con menos permisos | un rol más | una clave por cliente | usuarios externos en la app |

## 3. Decisión

Spring Security como **resource server** que valida los JWT del realm
(`KEYCLOAK_ISSUER_URI`). Los roles de Keycloak (realm y cliente) se convierten
en `ROLE_*` con jerarquía superadmin ⊃ admin ⊃ write ⊃ read y `external-read`
aparte. El acceso se declara **en el endpoint** con `@PreAuthorize` y ArchUnit
exige que todo endpoint lo declare. La PII se enmascara en la capa web antes de
responder a `external-read`. Health/info/prometheus sin token; el resto de
actuator, admin. `APP_SECURITY_ENABLED=false` solo en el entorno local.

## 4. Consecuencias

- Sin Keycloak alcanzable no se puede llamar a la API: en local se desactiva la
  seguridad a propósito y se avisa.
- Autorización por rol, no por dato: un `sap-read` ve todos los clientes. Si
  hace falta acotar por cliente/organización, se reabre este ADR (claims propios
  en el token o autorización por recurso).
- Logs y registro de accesos con PII: pendiente (SEC-3).
- Spec: [`../../sdd/common/seguridad-api.md`](../../sdd/common/seguridad-api.md).
  Guía: [`../../tools-integrations/KEYCLOAK.md`](../../tools-integrations/KEYCLOAK.md).
