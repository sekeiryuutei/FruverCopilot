-- Catalogo de ejemplo para arrancar. Los precios son ficticios: el administrador debe ajustarlos.
INSERT INTO product (name, unit) VALUES
 ('Papa criolla','LB'),('Papa pastusa','LB'),('Papa sabanera','LB'),
 ('Tomate chonto','LB'),('Cebolla cabezona','LB'),('Cebolla larga','LB'),
 ('Aguacate Hass','UNIDAD'),('Limon tahiti','LB'),('Zanahoria','LB'),
 ('Platano maduro','UNIDAD'),('Pina','UNIDAD'),('Papaya','UNIDAD'),('Cilantro','UNIDAD')
ON CONFLICT DO NOTHING;

INSERT INTO product_price (product_id, price_type, amount)
SELECT p.id, t.pt, ROUND(v.retail * t.factor, -1)
FROM product p
JOIN (VALUES ('Papa criolla',4500),('Papa pastusa',2200),('Papa sabanera',2400),('Tomate chonto',3800),
             ('Cebolla cabezona',2600),('Cebolla larga',3000),('Aguacate Hass',2500),('Limon tahiti',3200),
             ('Zanahoria',1800),('Platano maduro',1500),('Pina',5000),('Papaya',6000),('Cilantro',1000)) AS v(name, retail)
  ON v.name = p.name
CROSS JOIN (VALUES ('RETAIL',1.0),('WHOLESALE',0.87)) AS t(pt, factor)
ON CONFLICT DO NOTHING;
