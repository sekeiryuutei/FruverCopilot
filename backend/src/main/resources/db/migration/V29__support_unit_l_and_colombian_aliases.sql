-- V29: Soporte de unidad 'L' y productos/aliases colombianos tradicionales (Capira, Guayabo, Zapallo)

-- 1. Permitir unidad 'L' en order_item
ALTER TABLE order_item DROP CONSTRAINT IF EXISTS ck_order_item_unit;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_unit 
  CHECK (unit IS NULL OR (unit::text = ANY (ARRAY[
    'LB'::character varying, 
    'KG'::character varying, 
    'UNIDAD'::character varying,
    'CAJA'::character varying,
    'BULTO'::character varying,
    'ARROBA'::character varying,
    'ATADO'::character varying,
    'BOLSA'::character varying,
    'PAQUETE'::character varying,
    'MANOJO'::character varying,
    'L'::character varying
  ]::text[])));

-- 2. Permitir unidad 'L' en product
ALTER TABLE product DROP CONSTRAINT IF EXISTS product_unit_check;
ALTER TABLE product ADD CONSTRAINT product_unit_check 
  CHECK (unit::text = ANY (ARRAY[
    'LB'::character varying, 
    'KG'::character varying, 
    'UNIDAD'::character varying,
    'CAJA'::character varying,
    'BULTO'::character varying,
    'ARROBA'::character varying,
    'ATADO'::character varying,
    'BOLSA'::character varying,
    'PAQUETE'::character varying,
    'MANOJO'::character varying,
    'L'::character varying
  ]::text[]));

-- 3. Productos y aliases clave de plaza colombiana
INSERT INTO product(name, unit, active, aliases)
VALUES ('Papa Capira', 'LB', TRUE, 'capira, kapira, papa capira, capira grandecita, capira mediana, negra capira')
ON CONFLICT (lower(name)) DO UPDATE SET aliases = EXCLUDED.aliases, active = TRUE;

INSERT INTO product(name, unit, active, aliases)
VALUES ('Platano guayabo', 'LB', TRUE, 'guayabo, platano guayabo, plátano guayabo, maduro guayabo, verde guayabo')
ON CONFLICT (lower(name)) DO UPDATE SET aliases = EXCLUDED.aliases, active = TRUE;

INSERT INTO product(name, unit, active, aliases)
VALUES ('Zapallo', 'LB', TRUE, 'zapallo, zapallo entero, zapallo trozo, calabaza')
ON CONFLICT (lower(name)) DO UPDATE SET aliases = EXCLUDED.aliases, active = TRUE;

-- Precios base de referencia para que los productos puedan auto-aprobarse si no son editados a mano
DO $$
DECLARE
    capira_id BIGINT;
    guayabo_id BIGINT;
    zapallo_id BIGINT;
BEGIN
    SELECT id INTO capira_id FROM product WHERE lower(name) = 'papa capira';
    SELECT id INTO guayabo_id FROM product WHERE lower(name) = 'platano guayabo';
    SELECT id INTO zapallo_id FROM product WHERE lower(name) = 'zapallo';

    IF capira_id IS NOT NULL THEN
        INSERT INTO product_price(product_id, price_type, amount)
        VALUES (capira_id, 'RETAIL', 2000), (capira_id, 'WHOLESALE', 1500)
        ON CONFLICT (product_id, price_type) DO NOTHING;
    END IF;

    IF guayabo_id IS NOT NULL THEN
        INSERT INTO product_price(product_id, price_type, amount)
        VALUES (guayabo_id, 'RETAIL', 2000), (guayabo_id, 'WHOLESALE', 1600)
        ON CONFLICT (product_id, price_type) DO NOTHING;
    END IF;

    IF zapallo_id IS NOT NULL THEN
        INSERT INTO product_price(product_id, price_type, amount)
        VALUES (zapallo_id, 'RETAIL', 2500), (zapallo_id, 'WHOLESALE', 1800)
        ON CONFLICT (product_id, price_type) DO NOTHING;
    END IF;
END $$;
