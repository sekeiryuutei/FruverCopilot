-- V20: Renombrar Guayaba pera a Guayaba para mantener el estándar general del fruver
UPDATE product SET name = 'Guayaba' WHERE id = 87 AND name = 'Guayaba pera';

