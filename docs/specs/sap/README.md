# APIs SAP S/4HANA Cloud — catálogo de especificaciones

> Especificaciones OpenAPI oficiales de SAP S/4HANA Public Cloud usadas o
> previstas por el proyecto. Fuente única: el paquete OData de
> [**SAP Business Accelerator Hub**](https://api.sap.com/package/SAPS4HANACloud/odata),
> donde están todas las APIs necesarias.
>
> Las copias canónicas viven en `sap-api-models/specs/<dominio>/` (fuera de
> `src/main/resources` para no empaquetarlas en el JAR). Solo se generan
> modelos Java para las specs con consumidor en el código; para activar otra,
> añadir una `<execution>` en `sap-api-models/pom.xml`.

## Catálogo

| API | Dominio | api.sap.com | Fichero local | Formato | Modelos generados |
|---|---|---|---|---|---|
| Business Partner (A2X) | customer | [API_BUSINESS_PARTNER](https://api.sap.com/api/API_BUSINESS_PARTNER/overview) | [`specs/customer/API_BUSINESS_PARTNER.yaml`](../../../sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml) | OData V2 | ✅ `...api.customer.model` |
| Mandato SEPA (AR) | customer | [API_APAR_SEPA_MANDATE_SRV](https://api.sap.com/api/API_APAR_SEPA_MANDATE_SRV/overview) | [`specs/customer/API_APAR_SEPA_MANDATE_SRV.yaml`](../../../sap-api-models/specs/customer/API_APAR_SEPA_MANDATE_SRV.yaml) | OData V2 | ✅ `...api.customer.sepamandate.model` |
| Product Master (A2X) | article | [API_PRODUCT_SRV](https://api.sap.com/api/API_PRODUCT_SRV/overview) | [`specs/article/API_PRODUCT_SRV.yaml`](../../../sap-api-models/specs/article/API_PRODUCT_SRV.yaml) | OData V2 | ✅ `...api.article.product.model` |
| Material Stock (read) | article | [API_MATERIAL_STOCK_SRV](https://api.sap.com/api/API_MATERIAL_STOCK_SRV/overview) | [`specs/article/API_MATERIAL_STOCK_SRV.yaml`](../../../sap-api-models/specs/article/API_MATERIAL_STOCK_SRV.yaml) | OData V2 | ⬜ sin consumidor aún |
| Condiciones de precio (ventas) | article | [API_SLSPRICINGCONDITIONRECORD_SRV](https://api.sap.com/api/API_SLSPRICINGCONDITIONRECORD_SRV/overview) | [`specs/article/API_SLSPRICINGCONDITIONRECORD_SRV.yaml`](../../../sap-api-models/specs/article/API_SLSPRICINGCONDITIONRECORD_SRV.yaml) | OData V2 | ⬜ sin consumidor aún |
| Características de materiales | article | [API_CLFN_CHARACTERISTIC_SRV](https://api.sap.com/api/API_CLFN_CHARACTERISTIC_SRV/overview) | [`specs/article/API_CLFN_CHARACTERISTIC_SRV.json`](../../../sap-api-models/specs/article/API_CLFN_CHARACTERISTIC_SRV.json) | OData V2 | ⬜ sin consumidor aún |
| Números de serie | article | [CE_API_MATERIALSERIALNUMBER_0001](https://api.sap.com/api/sap-s4-CE_API_MATERIALSERIALNUMBER_0001-v1/overview) | [`specs/article/CE_API_MATERIALSERIALNUMBER_0001.yaml`](../../../sap-api-models/specs/article/CE_API_MATERIALSERIALNUMBER_0001.yaml) | OData V4 | ⬜ sin consumidor aún |
| Bancos (v3) | finance | [CE_BANK_0003](https://api.sap.com/api/CE_BANK_0003/resource/Bank) | [`specs/finance/CE_BANK_0003.yaml`](../../../sap-api-models/specs/finance/CE_BANK_0003.yaml) | OData V4 | ⬜ sin consumidor aún |
| Activos fijos (master data) | finance | [CE_FIXEDASSET_0001](https://api.sap.com/api/sap-s4-CE_FIXEDASSET_0001-v1/overview) | [`specs/finance/CE_FIXEDASSET_0001.yaml`](../../../sap-api-models/specs/finance/CE_FIXEDASSET_0001.yaml) | OData V4 | ⬜ sin consumidor aún |

> ⚠️ Las APIs `CE_*` son OData **V4**: sin envoltura `d` tampoco en respuestas,
> paginación con `@odata.nextLink` y sin fetch CSRF clásico de V2. El
> `WebClientSapClient` actual está probado contra V2; al activar una V4,
> revisar parseo de respuestas y cabeceras.

## Cómo actualizar una spec

```bash
# Descargar desde SAP Business Accelerator Hub (requiere sesión/API key)
curl -u user:pass \
  "https://api.sap.com/api/API_BUSINESS_PARTNER/openapi" \
  -o sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml
```

---

# SAP Business Partner API (A2X)

> Especificación OpenAPI oficial de SAP S/4HANA Cloud para la API Business Partner.
> Escenario de comunicación: **SAP_COM_0008** — *Business Partner, Customer and Supplier Integration*.

## Origen

- **Fuente:** [SAP Business Accelerator Hub](https://api.sap.com/api/API_BUSINESS_PARTNER/overview)
- **Servicio OData:** `/sap/opu/odata/sap/API_BUSINESS_PARTNER`
- **Formato:** OData v2 expuesto como OpenAPI 3.0 (`x-sap-api-type: ODATA`)
- **Versión S/4HANA:** 2508+
- **Fichero:** [`../../../sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml`](../../../sap-api-models/specs/customer/API_BUSINESS_PARTNER.yaml) (43156 líneas — copia canónica única, usada por `sap-api-models` para generar los modelos Java)

## Modelo de datos: Business Partner

En SAP S/4HANA, tanto **Clientes** como **Acreedores (Suppliers)** se modelan como
**Business Partner**. La categoría indica el *tipo de sujeto*, NO si es cliente o proveedor:

| Categoría | Significado |
|---|---|
| `BusinessPartnerCategory = 1` | *Persona* (persona física) |
| `BusinessPartnerCategory = 2` | *Organización* |
| `BusinessPartnerCategory = 3` | *Grupo* |

La condición de cliente o proveedor la dan los **roles** del BP
(`to_BusinessPartnerRole`): `FLCU01`/`FLCU00` para Customer y `FLVN01`/`FLVN00`
para Supplier. Para diferenciar un Customer de un Supplier en consultas se usan
las entidades especializadas, no la categoría:

```
GET /A_Customer   → Customers  (BPs con rol de cliente)
GET /A_Supplier   → Suppliers  (BPs con rol de proveedor)
```

## Endpoints relevantes para nuestro dominio

> Nota: los use cases `LookupCustomerUseCase`, `CreateBusinessPartnerUseCase` y
> `UpdateBusinessPartnerUseCase` citados abajo son **propuestas no implementadas**
> (ver [`FLOWS.md`](../../architecture/FLOWS.md)); los adaptadores OData sí existen.

### Operaciones sobre Business Partner (aggregate raíz)

| Endpoint | Método | Uso en nuestro proyecto | Flujo |
|---|---|---|---|
| `/A_BusinessPartner` | **GET** | Listar/todos (con `$top`, `$filter`, `$select`) | `LookupCustomerUseCase` (F2) |
| `/A_BusinessPartner` | **POST** | Crear un BP nuevo | `CreateBusinessPartnerUseCase` (F3) |
| `/A_BusinessPartner('{BP}')` | **GET** | Obtener un BP por código | `LookupCustomerUseCase.findById()` |
| `/A_BusinessPartner('{BP}')` | **PATCH** | Actualizar campos del BP | `UpdateBusinessPartnerUseCase` (F6) |
| `/A_BusinessPartner('{BP}')` | **DELETE** | Borrar BP | `DeleteCustomerUseCase` |

### Sub-entidades navegables (features de Customer)

| Endpoint | Entidad SAP | Feature nuestro dominio | Línea YAML |
|---|---|---|---|
| `/A_BusinessPartner('{BP}')/to_BusinessPartnerAddress` | Dirección | `ADDRESS` (`AddressData`) | 10136 |
| `/A_BusinessPartner('{BP}')/to_BusinessPartnerTax` | Datos fiscales | `FISCAL` (`FiscalData`) | 11035 |
| `/A_BusinessPartner('{BP}')/to_BusinessPartnerContact` | Contacto | `CONTACT` (`ContactData`) | 10630 |
| `/A_BusinessPartner('{BP}')/to_BusinessPartnerBank` | Datos bancarios | `BANKING` (`BankingData`) | 10485 |
| `/A_BusinessPartner('{BP}')/to_BusinessPartnerRole` | Roles SAP | `Customer` general | 10929 |

### Endpoints específicos de Customer (rol ya asignado)

| Endpoint | Método | Descripción | Línea YAML |
|---|---|---|---|
| `/A_Customer` | GET/POST | CRUD de Customers | 15494 |
| `/A_Customer('{Customer}')` | GET/PATCH/DELETE | Un Customer | 15735 |
| `/A_Customer('{Customer}')/to_CustomerCompany` | CRUD | Datos de sociedad | 16080 |
| `/A_Customer('{Customer}')/to_CustomerSalesArea` | CRUD | Área de ventas | 16292 |
| `/A_Customer('{Customer}')/to_CustomerTaxGrouping` | CRUD | Agrupación fiscal | 16584 |
| `/A_Customer('{Customer}')/to_CustomerText` | CRUD | Textos | 16673 |

### Endpoints específicos de Supplier

| Endpoint | Método | Descripción |
|---|---|---|
| `/A_Supplier` | GET/POST | CRUD de Suppliers |
| `/A_Supplier('{Supplier}')/to_SupplierCompany` | CRUD | Datos de sociedad |
| `/A_Supplier('{Supplier}')/to_SupplierPurchasingOrg` | CRUD | Org. de compras |

## Campos clave del payload de creación

### POST /A_BusinessPartner

```json
{
  "BusinessPartnerCategory": "2",
  "OrganizationBPName1": "Acme Corp",
  "BusinessPartnerGrouping": "BPEE",
  "SearchTerm1": "ACME"
}
```

### POST /A_BusinessPartner('{BP}')/to_BusinessPartnerAddress

```json
{
  "BusinessPartner": "BP_CODE",
  "AddressID": "1",
  "StreetName": "Calle Mayor 1",
  "CityName": "Madrid",
  "PostalCode": "28001",
  "Country": "ES",
  "Region": "Madrid"
}
```

### POST /A_BusinessPartner('{BP}')/to_BusinessPartnerTax

```json
{
  "BusinessPartner": "BP_CODE",
  "BPTaxType": "",
  "BPTaxNumber": "B12345678"
}
```

### POST /A_BusinessPartner('{BP}')/to_BusinessPartnerBank

```json
{
  "BusinessPartner": "BP_CODE",
  "BankIdentification": "BBVAESMM",
  "IBAN": "ES9121000418450200051332",
  "BankName": "BBVA"
}
```

## Convenciones OData v2

- **Wrapper `d:`** — el envoltorio `{"d": {...}}` aparece solo en las **respuestas** OData V2. Las peticiones POST/PATCH llevan la entidad **sin envolver** (el body es el JSON de la entidad directamente). Al parsear respuestas hay que desenvolver `d` (y `d.results` en colecciones).
- **CSRF** — necesario en POST/PATCH/DELETE. Header `x-csrf-token` obtenido con `GET` + header `x-csrf-token: Fetch`. Manejo en `common/sap/odata/CsrfTokenProvider.java`.
- **ETag** — respuestas incluyen `ETag`. Para PATCH/DELETE, se debe enviar header `If-Match` con el valor del ETag.
- **Batch** — el API soporta `$batch` para enviar múltiples operaciones en una sola petición (futuro).

## Mapping features de dominio ↔ SAP BP API

| Feature | Puerto | DTO | Endpoint SAP | Campos mapeados |
|---|---|---|---|---|
| ADDRESS | `AddressSapPort` | `BtpAddressDto` | `to_BusinessPartnerAddress` | Street, City, PostalCode, Country, Region |
| FISCAL | `FiscalSapPort` | `BtpFiscalDto` | `to_BusinessPartnerTax` | TaxNumber, VATNumber, LegalName, TaxResidency |
| CONTACT | `ContactSapPort` | `BtpContactDto` | `to_BusinessPartnerContact` | Email, Phone, Fax, Website |
| BANKING | `BankingSapPort` | `S4BankingDto` | `to_BusinessPartnerBank` | IBAN, BIC, mandates |
| CUSTOMER | `CustomerSapOutboundPort` | `BtpCustomerDto` | `A_BusinessPartner` | BusinessPartner, Name, Status |

> **Nota:** Los nombres de campo en los DTOs actuales no coinciden exactamente con el API de SAP.
> Los DTOs se crearon para la API BTP y se reutilizan para OData como punto de partida.
> Fase futura: alinear los DTOs con los schemas exactos de este fichero YAML.
