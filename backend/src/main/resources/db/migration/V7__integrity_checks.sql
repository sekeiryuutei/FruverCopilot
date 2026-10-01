-- Red de seguridad en la base: ninguna capa de la aplicacion puede dejar pasar cantidades o precios negativos.
-- NOT VALID = se exige en toda fila nueva o modificada sin recorrer/rechazar filas historicas al migrar.
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_quantity     CHECK (quantity IS NULL OR quantity > 0) NOT VALID;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_manual_price CHECK (manual_price IS NULL OR manual_price >= 0) NOT VALID;
ALTER TABLE sale       ADD CONSTRAINT ck_sale_total              CHECK (total >= 0) NOT VALID;
ALTER TABLE sale_item  ADD CONSTRAINT ck_sale_item_quantity      CHECK (quantity > 0) NOT VALID;
ALTER TABLE sale_item  ADD CONSTRAINT ck_sale_item_prices        CHECK (applied_price >= 0 AND subtotal >= 0) NOT VALID;
