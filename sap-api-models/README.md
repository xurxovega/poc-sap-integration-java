# sap-api-models — modelos SAP generados desde specs OpenAPI oficiales

Módulo reactor dedicado a las **specs OpenAPI oficiales de SAP** y los
modelos Java que genera `openapi-generator-maven-plugin`. Es un
**Published Language** (DDD): lenguaje definido por SAP y consumido por
los bounded contexts. **No es el Shared Kernel** — eso es `common`.

## Qué hay aquí

- **Specs oficiales de SAP** en `specs/<dominio>/` (FUERA de
  `src/main/resources`, para no empaquetar el YAML en el JAR):
  - `specs/customer/API_BUSINESS_PARTNER.yaml` — Business Partner (A2X,
    SAP_COM_0008), 43156 líneas.
- **Modelos Java generados** en `target/generated-sources/openapi/` (no
  commiteados). Se generan en el goal `generate-sources`:

  ```bash
  mvn generate-sources -pl sap-api-models
  ```

  Salida: paquete `com.poc.sap.integration.api.<dominio>.model`,
  ej. `com.poc.sap.integration.api.customer.model.A_BusinessPartnerAddress`.

## Quién los consume

- `customer` usa los modelos generados para los adaptadores OData
  (`customer/adapters/sap/odata/`), **no DTOs manuales**.
- `article` y `supplier` consumirán cuando aborden sus integraciones.

Catálogo de contratos SAP en
[`docs/sdd/sap-api-catalog.md`](../docs/sdd/sap-api-catalog.md).

## Cómo añadir una spec SAP

1. Colocar el YAML en `sap-api-models/specs/<dominio>/` (fuera de
   `src/main/resources`).
2. Añadir un `<execution>` en el `openapi-generator-maven-plugin` con
   `inputSpec` apuntando al YAML.
3. Generar: `mvn generate-sources -pl sap-api-models`.
4. Alta en [`docs/sdd/sap-api-catalog.md`](../docs/sdd/sap-api-catalog.md).
5. Consumir desde el bounded context con el paquete generado.

> **Issue conocido (DX-7)**: el generador falla de forma intermitente en
> Windows si `target/` no está limpio (`Unable to delete original source
> file`). Mitigado fijando `deleteOutputDirectory=false` en la 2.ª y 3.ª
> ejecución. Si vuelve a fallar, ejecutar antes `mvn clean -pl sap-api-models`.