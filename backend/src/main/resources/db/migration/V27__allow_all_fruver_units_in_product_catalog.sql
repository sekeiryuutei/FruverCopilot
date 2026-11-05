-- V27: Permitir todas las unidades de fruver en product (CAJA, BULTO, ARROBA, ATADO, BOLSA, PAQUETE, MANOJO)
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
    'MANOJO'::character varying
  ]::text[]));

