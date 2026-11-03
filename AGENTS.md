# Reglas para agentes de IA — FruverCopilot (piloto)

Estamos en **piloto**. La prioridad es no enviar mensajes reales, no imprimir en hardware real y no tocar producción sin autorización.

## Pruebas permitidas

- Cambio normal: `mvn -f backend/pom.xml -Punit test`
  - Rápido, sin Docker, sin IA real, sin WhatsApp y sin impresora.
- Cambio de facturación/impresión: `mvn -f backend/pom.xml -Pintegration-safe test`
  - Usa Testcontainers, pero deja WhatsApp y la impresora mockeados/desactivados.
- Un solo test: `mvn -f backend/pom.xml -Pintegration-safe -Dtest=NombreDelTest test`

## Prohibido sin autorización explícita

- Ejecutar `mvn test` completo. Incluye `ExtractionEvalTest` (IA real) y pruebas de integración que pueden tardar o tocar servicios externos.
- Ejecutar `ExtractionEvalTest` con claves reales.
- Usar el `.env` de producción para pruebas o copiar secretos a logs/reportes.
- Enviar WhatsApp real, reacciones reales o imprimir en la impresora física.
- Modificar `.env`, credenciales, historial de Git o código de producción.

## Si algo falla

- Reportar el fallo y el comando exacto.
- No “arreglar” producción ni cambiar flags para que el test pase.
- Los reportes detallados van en `docs/auditorias/2026-10-25/`.
