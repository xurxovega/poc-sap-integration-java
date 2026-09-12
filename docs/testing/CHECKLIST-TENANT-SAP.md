# Checklist — primera sesión contra el tenant SAP de test

> Lo que hay que comprobar **antes de diseñar** el upsert (plan de acción, Fase 3)
> y lo que la Fase 3.2 dejó «pendiente de validar». Cada punto tiene la petición
> exacta y qué respuesta cambia qué decisión. Requiere `test.env` con
> credenciales reales, `SAP_AUTH_ALLOW_STUB=false` y `SAP_S4_CSRF_ENABLED=true`.
> Usa un Business Partner de pruebas: las escrituras se facturan y persisten.

Variables: `$S4` = `SAP_S4_BASE_URL`, `$TOKEN` = token OAuth2 del communication
arrangement (o `-u user:pass` con basic). `BP` = un BP de pruebas existente.

## 0. Conectividad y CSRF

```bash
curl -si "$S4/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner?\$top=1" \
  -H "Authorization: Bearer $TOKEN" -H "x-csrf-token: Fetch" -H "Accept: application/json"
```
Esperado: `200`, cabecera `x-csrf-token`, cookies `SAP_SESSIONID*`. Si `401`:
credenciales; si `403` sin token: el communication arrangement no incluye la API.

## 1. ¿PATCH parcial o payload completo? (decide el diseño del upsert)

```bash
# leer y guardar el ETag
curl -si "$S4/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner('BP')" \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/json"
# PATCH de un solo campo con If-Match
curl -si -X PATCH "$S4/sap/opu/odata/sap/API_BUSINESS_PARTNER/A_BusinessPartner('BP')" \
  -H "Authorization: Bearer $TOKEN" -H "x-csrf-token: <token>" -H "Cookie: <cookies>" \
  -H "If-Match: <etag>" -H "Content-Type: application/json" \
  -d '{"OrganizationBPName2":"prueba-patch"}'
```
| Resultado | Decisión |
|---|---|
| `204` y el resto de campos intactos | Upsert = lookup → `PATCH` parcial con `If-Match`. El atajo «sin cambios reales» sigue valiendo |
| `400`/`412` exigiendo más campos o `If-Match: *` | Upsert con payload completo; medir coste (los upserts se facturan) |
| `428 Precondition Required` | `If-Match` obligatorio: el ETag hay que persistirlo en la imagen |

## 2. Dirección: `AddressID` y deep insert

- Crear un BP nuevo con `to_BusinessPartnerAddress` embebido (deep insert) y
  anotar el `AddressID` que devuelve. → Confirma que hay que **persistir
  `AddressID`** en la imagen para los `PATCH` posteriores.
- `PATCH A_BusinessPartnerAddress(BusinessPartner='BP',AddressID='<id>')`
  cambiando `CityName`. → Si `204`, la modificación de dirección es un PATCH,
  no un POST (hoy cada actualización crea una dirección nueva, auditoría B3).

## 3. Contacto: email y teléfono

- `POST A_AddressEmailAddress` con `{AddressID, Person:"", OrdinalNumber:"1", EmailAddress}`
  y `POST A_AddressPhoneNumber` con `{AddressID, ..., PhoneNumber, PhoneNumberType:"1"}`.
  → Confirma claves y si `Person`/`OrdinalNumber` los asigna SAP. Decide el
  adaptador de CONTACT (PRD-2).

## 4. Banco (Fase 3.2, pendiente de validar)

- `POST A_BusinessPartnerBank` con `{BusinessPartner, BankIdentification:"0001", IBAN, BankCountryKey:"ES"}`
  **sin** `BankNumber`. → Si `400` pidiendo `BankNumber`, hace falta IBAN-only
  activado en el tenant o derivar `BankNumber` del IBAN.

## 5. Mandato SEPA (Fase 3.2, pendiente de validar)

- `GET API_APAR_SEPA_MANDATE_SRV/SEPAMandateSet?$top=1` → valores reales de
  `SEPAMandateApplication`, `SenderType`, `SEPAMandateStatus`.
  Comparar con lo que enviamos: `Application=F`, `SenderType=BUS1006`,
  estados `1/3/4` ([`../sdd/customer/sincronizacion-datos-bancarios.md`](../sdd/customer/sincronizacion-datos-bancarios.md) R-5).
- `PATCH SEPAMandateSet(Creditor='<id>',SEPAMandate='<ref>')` con
  `{"SEPAMandateStatus":"3"}` → confirma que revocar es un cambio de estado.

## 6. Baja del Business Partner (modelo de bloqueo)

- `DELETE A_BusinessPartner('BP')` → lo esperable es `405` o `400`: S/4 no
  borra BPs. Entonces la baja es `PATCH` con `BusinessPartnerIsBlocked: true`
  (y/o `to_Customer` con bloqueo de pedidos). Decide el adaptador de
  [`../sdd/customer/baja-cliente.md`](../sdd/customer/baja-cliente.md) R-2.

## 7. Idempotencia real

- Repetir un `POST A_BusinessPartnerAddress` idéntico con la misma
  `Idempotency-Key`. → Si crea dos direcciones, queda demostrado R-7 de
  [`../sdd/common/resiliencia-cliente-sap.md`](../sdd/common/resiliencia-cliente-sap.md):
  la idempotencia la da el upsert, no la cabecera.

## Registro

Anotar cada punto con petición, respuesta (status + cuerpo recortado) y
decisión tomada en `docs/incidencias/` o directamente en el spec afectado (§10).
