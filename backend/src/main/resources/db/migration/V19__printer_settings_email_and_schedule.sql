-- Configuración dinámica de tipo de impresión, correo Brother y horarios sin valores quemados
ALTER TABLE printer_setting
  ADD COLUMN IF NOT EXISTS office_print_type VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
  ADD COLUMN IF NOT EXISTS office_email VARCHAR(120) NOT NULL DEFAULT '23363927290@print.brother.com',
  ADD COLUMN IF NOT EXISTS home_print_type VARCHAR(20) NOT NULL DEFAULT 'ESCPOS',
  ADD COLUMN IF NOT EXISTS home_email VARCHAR(120) NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS schedule_office_start VARCHAR(10) NOT NULL DEFAULT '04:00',
  ADD COLUMN IF NOT EXISTS schedule_office_end VARCHAR(10) NOT NULL DEFAULT '19:00',
  ADD COLUMN IF NOT EXISTS smtp_host VARCHAR(120) NOT NULL DEFAULT 'smtp.gmail.com',
  ADD COLUMN IF NOT EXISTS smtp_port INT NOT NULL DEFAULT 465,
  ADD COLUMN IF NOT EXISTS smtp_user VARCHAR(120) NOT NULL DEFAULT 'citas.drjorgejimenez@gmail.com',
  ADD COLUMN IF NOT EXISTS smtp_password VARCHAR(120) NOT NULL DEFAULT 'tbizucojkyxzoows',
  ADD COLUMN IF NOT EXISTS smtp_ssl BOOLEAN NOT NULL DEFAULT TRUE;

