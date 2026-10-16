-- V8: endurece integridad sin romper datos existentes (todo NOT VALID + IF NOT EXISTS).
-- 1) order_item.unit solo valores del sistema (antes solo lo validaba la app).
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_unit
  CHECK (unit IS NULL OR unit IN ('LB','KG','UNIDAD')) NOT VALID;
-- 2) Evita duplicar el mismo producto+unidad dentro de un pedido (la app ya lo rechaza con ITEM_DUPLICATED).
CREATE UNIQUE INDEX IF NOT EXISTS ux_order_item_order_product_unit
  ON order_item (order_id, product_id, unit) WHERE product_id IS NOT NULL;
-- 3) print_job: buscar por venta (processOne/reprint) sin full scan.
CREATE INDEX IF NOT EXISTS ix_print_job_sale ON print_job (sale_id, status);
-- 4) Auditoría de logins: buscar por acción+fecha.
CREATE INDEX IF NOT EXISTS ix_audit_action_at ON audit_log (action, at);
