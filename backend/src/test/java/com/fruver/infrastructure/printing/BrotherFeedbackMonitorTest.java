package com.fruver.infrastructure.printing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrotherFeedbackMonitorTest {

    @Test
    @DisplayName("Debe clasificar como CONFIRMED el correo estándar de Brother Cloud")
    void testEvaluateMessageBrotherSuccess() {
        String from = "FromBrotherDevice@brother.com";
        String subject = "Trabajo de impresión de correo electrónico recibido";
        String body = """
                Gracias por usar el servicio de impresión de correos electrónicos de Brother.
                Su trabajo de impresión está preparándose para imprimirse.
                """;

        BrotherFeedbackMonitorService.FeedbackResult res = BrotherFeedbackMonitorService.evaluateMessage(from, subject, body);
        assertEquals(BrotherFeedbackMonitorService.FeedbackType.CONFIRMED, res.type());
        assertTrue(res.summary().contains("Recepción confirmada"));
    }

    @Test
    @DisplayName("Debe clasificar como FAILED cuando Brother reporta un error de formato o hardware")
    void testEvaluateMessageBrotherError() {
        String from = "FromBrotherDevice@brother.com";
        String subject = "Fallo en el trabajo de impresión de correo electrónico";
        String body = "El trabajo no se pudo imprimir debido a un error de formato no admitido en el archivo adjunto.";

        BrotherFeedbackMonitorService.FeedbackResult res = BrotherFeedbackMonitorService.evaluateMessage(from, subject, body);
        assertEquals(BrotherFeedbackMonitorService.FeedbackType.FAILED, res.type());
        assertTrue(res.summary().contains("Fallo"));
    }

    @Test
    @DisplayName("Debe clasificar como FAILED los rebotes de correo de Mailer-Daemon")
    void testEvaluateMessageMailerDaemonBounce() {
        String from = "mailer-daemon@googlemail.com";
        String subject = "Delivery Status Notification (Failure)";
        String body = "Message blocked: The user you are trying to contact is receiving mail at a rate that prevents additional messages.";

        BrotherFeedbackMonitorService.FeedbackResult res = BrotherFeedbackMonitorService.evaluateMessage(from, subject, body);
        assertEquals(BrotherFeedbackMonitorService.FeedbackType.FAILED, res.type());
        assertTrue(res.summary().contains("Rebote de entrega"));
    }

    @Test
    @DisplayName("Debe ignorar correos no relacionados con la impresora")
    void testEvaluateMessageUnrelatedIgnored() {
        String from = "promociones@banco.com";
        String subject = "Estado de cuenta mensual";
        String body = "Estimado cliente, adjunto su extracto...";

        BrotherFeedbackMonitorService.FeedbackResult res = BrotherFeedbackMonitorService.evaluateMessage(from, subject, body);
        assertEquals(BrotherFeedbackMonitorService.FeedbackType.IGNORED, res.type());
    }

    @Test
    @DisplayName("Monitor no debe fallar si printerConfig es nulo")
    void testPollInboxWithoutConfigGracefullySkips() {
        BrotherFeedbackMonitorService service = new BrotherFeedbackMonitorService(null, null);
        assertDoesNotThrow(service::pollBrotherInbox);
    }
}
