-- V23: Permitir unidades mayoristas (CAJA, BULTO, ARROBA, ATADO, BOLSA, etc.) y aliases colombianos (oro miel, epesina)

-- 1. Modificar restricción de unidades en order_item para admitir unidades mayoristas
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
    'MANOJO'::character varying
  ]::text[])));

-- 2. Aliases colombianos clave: oro miel -> Pina, epesina -> Espinaca
UPDATE product 
SET aliases = 'oro miel, piña oro miel, pina oro miel, piña gold, piña' 
WHERE id = 11 OR lower(name) = 'pina';

UPDATE product 
SET aliases = 'espinaca, epesina, espinacas' 
WHERE id = 40 OR lower(name) = 'espinaca';

UPDATE product
SET aliases = 'banano, bananos, banano criollo'
WHERE id = 4 OR lower(name) = 'banano';

UPDATE product
SET aliases = 'papa, papas, papa parda, papa común, papa comun'
WHERE id = 1 OR lower(name) = 'papa';

