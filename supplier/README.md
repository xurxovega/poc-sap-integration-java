# supplier — dominio de proveedor (placeholder)

Dominio **no operativo**. El módulo existe para mantener la simetría del
reactor Maven y dejar preparada la plaza, pero:

- `supplier/src/main/resources/application.yml` declara
  `spring.main.lazy-initialization: true`.
- La única clase es `bootstrap/SupplierApplicationPlaceholder` con un
  `main()` que **lanza `UnsupportedOperationException("Dominio SUPPLIER
  pendiente de implementar (OVERVIEW.md §2)")`**.
- El spec `docs/sdd/supplier/sincronizacion-proveedor.md` **no está
  escrito** todavía (fila vacía en `docs/sdd/README.md` §5).

## Estado

Marcado como 💡 `solicitada` en `docs/MEJORAS-Y-PROPUESTAS.md` (PRD-1:
"**Dominio `supplier` real**").

## Próximos pasos cuando se aborde

1. Spec primero: `docs/sdd/supplier/sincronizacion-proveedor.md` desde
   [`_template/feature.md`](../docs/sdd/_template/feature.md).
2. Módulo `supplier/` con paquetes `domain/application/adapters/bootstrap`.
3. Dependencia a `common` (sin duplicar `SyncStateMachine` ni
   `MongoSyncStateRepository`).
4. Tests en rojo primero (TDD).
5. Aggregate, puertos (`LegacyRepositoryPort`, `ImageStorePort`,
   `HistoryIndexerPort`) + `SyncSupplierUseCase`.
6. Adaptadores (Postgres + Mongo + ES + SAP) con sus tests; SAP contra
   WireMock.
7. `@SpringBootApplication` + `@KafkaListener(outbox.SUPPLIER)` +
   `POST /suppliers/sync`, con smoke test de contexto.
8. `application.yml` con `spring.config.import=application-common.yml` y
   sus variables de entorno.

Mientras tanto, **no tocar este módulo** salvo para hacerlo arrancar (lo
que requerirá primero implementar la feature).