-- V14: Permitir unidad nula en sale_item para pedidos mayoristas y flexibilidad de factura
ALTER TABLE sale_item ALTER COLUMN unit DROP NOT NULL;

