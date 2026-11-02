-- V22: Agregar Cimarrón, hierbas aromáticas al catálogo y soporte de alias de búsqueda
ALTER TABLE product ADD COLUMN IF NOT EXISTS aliases text;

-- Insertar nuevas hierbas comunes de fruver / plazas de mercado
INSERT INTO product (name, unit, active, aliases) VALUES
    ('Cilantro cimarron', 'UNIDAD', TRUE, 'cimarron, cilantro cimarron, cilantro de hoja ancha, recao, cimarrona'),
    ('Poleo', 'UNIDAD', TRUE, 'poleo, menta poleo'),
    ('Guascas', 'UNIDAD', TRUE, 'guascas, guasca'),
    ('Eneldo', 'UNIDAD', TRUE, 'eneldo'),
    ('Ruda', 'UNIDAD', TRUE, 'ruda'),
    ('Toronjil', 'UNIDAD', TRUE, 'toronjil, melisa'),
    ('Prontoalivio', 'UNIDAD', TRUE, 'prontoalivio, pronto alivio'),
    ('Estragon', 'UNIDAD', TRUE, 'estragon'),
    ('Salvia', 'UNIDAD', TRUE, 'salvia'),
    ('Calendula', 'UNIDAD', TRUE, 'calendula'),
    ('Ortiga', 'UNIDAD', TRUE, 'ortiga'),
    ('Puerro', 'UNIDAD', TRUE, 'puerro, cebolla puerro'),
    ('Hierbas de sopa', 'UNIDAD', TRUE, 'hierbas de sopa, hierbas para sopa, atado de sopa, surtido de hierbas')
ON CONFLICT (lower(name)) DO UPDATE SET
    active = TRUE,
    aliases = COALESCE(product.aliases, EXCLUDED.aliases);

-- Actualizar alias en productos existentes para máxima precisión en IA
UPDATE product SET aliases = 'cilantro, atado de cilantro' WHERE lower(name) = 'cilantro' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'apio, tallo de apio, atado de apio' WHERE lower(name) = 'apio' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'perejil crespo' WHERE lower(name) = 'perejil crespo' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'perejil liso, perejil común' WHERE lower(name) = 'perejil liso' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'hierbabuena, yerbabuena' WHERE lower(name) = 'hierbabuena' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'menta' WHERE lower(name) = 'menta' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'albahaca' WHERE lower(name) = 'albahaca' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'romero' WHERE lower(name) = 'romero' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'tomillo' WHERE lower(name) = 'tomillo' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'laurel, hojas de laurel' WHERE lower(name) = 'laurel' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'oregano, oregano fresco' WHERE lower(name) = 'oregano fresco' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'limonaria, hierba limon' WHERE lower(name) = 'limonaria' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'manzanilla' WHERE lower(name) = 'manzanilla' AND (aliases IS NULL OR aliases = '');
UPDATE product SET aliases = 'cebollin, ciboulette' WHERE lower(name) = 'cebollin' AND (aliases IS NULL OR aliases = '');

-- Precios de referencia para las nuevas hierbas
INSERT INTO product_price (product_id, price_type, amount)
SELECT p.id, t.pt, ROUND(v.retail * t.factor, -1)
FROM product p
JOIN (VALUES
    ('Cilantro cimarron', 1000),
    ('Poleo', 1000),
    ('Guascas', 1200),
    ('Eneldo', 1200),
    ('Ruda', 1000),
    ('Toronjil', 1000),
    ('Prontoalivio', 1000),
    ('Estragon', 1500),
    ('Salvia', 1200),
    ('Calendula', 1200),
    ('Ortiga', 1000),
    ('Puerro', 2000),
    ('Hierbas de sopa', 2000)
) AS v(name, retail)
  ON lower(v.name) = lower(p.name)
CROSS JOIN (VALUES ('RETAIL', 1.0), ('WHOLESALE', 0.87)) AS t(pt, factor)
ON CONFLICT (product_id, price_type) DO UPDATE SET amount = EXCLUDED.amount;

-- Limpiar/cancelar pedidos vacíos que no tienen items de fruver (como comprobantes o saludos)
UPDATE orders
SET status = 'CANCELLED',
    review_status = NULL,
    conversation_status = 'COMPLETED',
    ai_note = COALESCE(ai_note, 'Interacción sin productos de fruver (descartada automáticamente)')
WHERE status IN ('RECEIVED','PROCESSING','REVIEW')
  AND NOT EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id = orders.id);

