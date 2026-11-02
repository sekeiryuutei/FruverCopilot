-- V21: Soporte de selector de proveedor de WhatsApp (META_CLOUD_API vs BAILEYS_QR)
ALTER TABLE printer_setting ADD COLUMN IF NOT EXISTS whatsapp_provider VARCHAR(30) DEFAULT 'BAILEYS_QR';
UPDATE printer_setting SET whatsapp_provider = 'BAILEYS_QR' WHERE whatsapp_provider IS NULL;

