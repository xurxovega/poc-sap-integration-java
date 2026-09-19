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
| `428 Precondition Required` | `If-Match` obligatorio. **No se persiste el ETag**: se relee en el lookup inmediatamente anterior al PATCH ([`../sdd/common/upsert-idempotente-sap.md`](../sdd/common/upsert-idempotente-sap.md) R-5) |

**Comprobar además, que es de lo que depende todo el upsert:**

- [ ] ¿El `GET` de `A_BusinessPartner` devuelve **cabecera `ETag`**, o solo
      `__metadata.etag` en el cuerpo? El cliente lee primero la cabecera y cae al
      cuerpo; confirmar cuál llega de verdad.
- [ ] ¿El `GET` por clave de un BP **inexistente** responde `404`, o `200` con un
      cuerpo vacío? Toda la decisión alta/actualización se apoya en que solo el
      `404` significa «no lo tiene» (R-2). Si responde otra cosa, hay que ajustar
      `ODataLookups`.
- [ ] ¿`BusinessPartnerGrouping = "BPEE"` deja **fijar la clave externa**, de modo
      que `BusinessPartner` sea nuestro `entityId`? Si SAP asigna la suya, el
      agregado también tendría que persistir su clave.
- [ ] Repetir el `PATCH` con el **mismo ETag ya consumido**: debe dar `412`, que
      es lo que hace seguro reintentar un `PATCH` con `If-Match` (R-9 de
      [`../sdd/common/resiliencia-cliente-sap.md`](../sdd/common/resiliencia-cliente-sap.md)).

## 2. Dirección: `AddressID` y deep insert

- Crear un BP nuevo con `to_BusinessPartnerAddress` embebido (deep insert) y
  anotar el `AddressID` que devuelve. → Confirma que hay que **persistir
  `AddressID`** en la imagen para los `PATCH` posteriores.
- `PATCH A_BusinessPartnerAddress(BusinessPartner='BP',AddressID='<id>')`
  cambiando `CityName`. → Si `204`, la modificación de dirección es un PATCH,
  no un POST (hoy cada actualización crea una dirección nueva, auditoría B3).
- [ ] **Formato del `AddressID`**: ¿longitud fija con ceros a la izquierda
      (`0000123456`)? Es lo que se guarda en `sap_keys` y lo que se concatena en
      la URL: si SAP lo devuelve sin relleno pero lo exige con él, el `GET` por
      clave dará `404` y **cada ciclo creará una dirección nueva**.
- [ ] `GET A_BusinessPartner('BP')/to_BusinessPartnerAddress?$top=2` en un BP con
      **una** dirección y en otro con **dos**: confirmar que la respuesta es
      `{"d":{"results":[...]}}` y que trae `__metadata.etag` por elemento. Con dos
      resultados nuestro adaptador se declara no concluyente y **no escribe**.

## 3. Contacto: email y teléfono

**Decidido** (PRD-2, [`../sdd/customer/sincronizacion-contacto.md`](../sdd/customer/sincronizacion-contacto.md)):
`ContactData` va a las entidades de comunicación de la dirección, no a
`A_BusinessPartnerContact`, que exige un `BusinessPartnerPerson` que no tenemos.
Queda confirmar los valores de la clave:

- `POST A_AddressEmailAddress` con `{AddressID, Person:"", OrdinalNumber:"0", EmailAddress}`
  y `POST A_AddressPhoneNumber` con `{AddressID, Person:"", OrdinalNumber:"0", PhoneNumber}`.
- [ ] ¿`Person: ""` es válido para la dirección de una **organización**, o SAP
      exige otro valor?
- [ ] ¿`OrdinalNumber` lo fijamos nosotros (`"0"`) o lo **asigna SAP**? Si lo
      asigna, es otra clave a persistir en `sap_keys`, una por tipo de
      comunicación.
- [ ] `GET A_AddressEmailAddress(AddressID='<id>',Person='',OrdinalNumber='0')` de
      una entrada inexistente: debe dar `404` (R-2 del upsert).
- [ ] `A_AddressHomePageURL`: comprobar la clave completa con
      `ValidityStartDate=datetime'0001-01-01T00:00:00'` e `IsDefaultURLAddress=true`,
      y si el `PATCH` parcial de `WebsiteURL` se acepta.
- [ ] `PATCH` de cada una de las cuatro con `If-Match`: ¿`204`?

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

## 8. Artículo: ruta real y campos mínimos

El adaptador actual (`article/adapters/sap/S4ArticleAdapter.java:25`) usa por
defecto la ruta `/sap/opu/odata/sap/API_PRODUCT` con los campos `Product`,
`Description`, `Category`, `BaseUnit`, `Status`
(`S4ProductDto.java:14-18`). Verificado contra la spec del proyecto
(`sap-api-models/specs/article/API_PRODUCT_SRV.yaml`): la ruta real de la
entidad es **`API_PRODUCT_SRV/A_Product`** (`API_PRODUCT_SRV.yaml:78`), y
`Description`, `Category` y `Status` **no existen** como propiedades de
`A_ProductType` — la spec solo declara, entre otros, `Product`, `ProductType`,
`BaseUnit`, `ProductGroup`, `Division` (`API_PRODUCT_SRV.yaml`, esquema
`API_PRODUCT_SRV.A_ProductType`); la descripción de texto libre vive en la
entidad relacionada `to_Description` (`API_PRODUCT_SRV.yaml:220`), no en el
`A_Product` raíz. Con la configuración de hoy, cualquier POST/PATCH de
artículo falla contra un tenant real (404 por ruta, o 400 por campos que la
entidad no reconoce) — hallazgo **2A-8** de la auditoría del 2026-09-18.

```bash
curl -si "$S4/sap/opu/odata/sap/API_PRODUCT_SRV/A_Product?\$top=1" \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/json"
```

| Punto a comprobar | Qué decide |
|---|---|
| Ruta real (`API_PRODUCT_SRV/A_Product` vs la del `application.yml`, propiedad `sap.article.s4.path`) | Corregir el valor por defecto de la propiedad |
| Campos mínimos que S/4 exige para crear un producto (`Product`, `ProductType`, `BaseUnit` son candidatos según la spec; a confirmar cuáles son obligatorios en el tenant) | Rediseñar `S4ProductDto` contra la spec real, no contra el código actual |
| Numeración interna vs externa del producto (`Product` como clave, longitud y si el tenant asigna el número o lo aporta el llamador) | Cómo mapear el código de producto legacy al `Product` de S/4 |
| Campos de texto (`to_Description`) y de planta (`to_Plant`) | Si el alta de artículo es una sola llamada o un deep insert con subentidades |

## 9. BTP: URL, spec y prueba en ambos sentidos

Cubre lo que falta para pasar del estado actual de la vía BTP — *"el servicio
BTP funciona por sí solo y se ha probado en el espacio del propietario, pero
falta integrarlo con esta aplicación"* (D-10, [`INTEGRATION-PATTERNS.md`](../architecture/INTEGRATION-PATTERNS.md))
— a una integración probada extremo a extremo. Variable nueva: `$BTP` =
`SAP_BTP_BASE_URL`.

| # | Punto a comprobar | Qué decide |
|---|---|---|
| 1 | URL real del servicio BTP del propietario (`$BTP`), y si coincide con `sap.btp.base-url` en `application-common.yml` | Si el `ConfigMap` de despliegue (`deploy/k8s/base/common.yaml`) apunta a algo real o a un contrato placeholder |
| 2 | Spec del servicio BTP (¿tiene OpenAPI publicado? ¿dónde vive?) — a confirmar con el propietario, ver Confluence en [`INTEGRATION-PATTERNS.md`](../architecture/INTEGRATION-PATTERNS.md) | Si los paths `/sap/btp/odata/*` y los DTOs de `Btp*Adapter` coinciden con el contrato real, o hay que reescribirlos |
| 3 | Auth del servicio real: xsuaa del propietario, `client_id`/`client_secret` y `token endpoint` reales (hoy `OAuth2TokenClient` cachea el token con fallback a stub si falta configuración) | Si el flujo OAuth2 xsuaa ya implementado sirve tal cual o necesita ajuste (scopes, audiencia) |
| 4 | Prueba **app → SAP a través de BTP**: enviar una feature real (p. ej. `ADDRESS`) con `sap.odata.address.enabled=false` (BTP activo, valor por defecto) contra `$BTP` y verificar en el lado SAP que el dato llega | Confirma o descarta el riesgo abierto 2B-4 en ese sentido |
| 5 | Prueba **SAP → app** (si el propietario expone un mecanismo de notificación desde su servicio BTP): confirmar formato y autenticación de la llamada entrante | Si hace falta un endpoint nuevo o el modo pull (Patrón 3, propuesto, aún sin código) cubre este caso |

Sin estas dos comprobaciones, la familia BTP sigue activa por defecto
(`sap.odata.<feature>.enabled=false` salvo que se diga lo contrario) contra un
servicio que nunca se ha probado desde esta aplicación: es exactamente el
riesgo 2B-4, cuya mitigación propuesta (guard de arranque) está en
[`MEJORAS-Y-PROPUESTAS.md`](../MEJORAS-Y-PROPUESTAS.md).

## Registro

Anotar cada punto con petición, respuesta (status + cuerpo recortado) y
decisión tomada en `docs/incidencias/` o directamente en el spec afectado (§10).
