package com.fruver.integration;

import com.fruver.application.port.MessagingGateway;
import com.fruver.infrastructure.whatsapp.strategy.WhatsAppServiceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Base para pruebas de integracion que nunca deben tocar WhatsApp real.
 *
 * Reemplaza el contexto de WhatsApp por un mock primario y devuelve LOCAL_ONLY.
 * Así se conserva el registro en conversation_message sin abrir red, bridge ni Meta.
 */
abstract class SafeMessagingSupport {

    @MockitoBean
    WhatsAppServiceContext whatsAppContext;

    @BeforeEach
    void stubSafeMessaging() {
        when(whatsAppContext.sendText(anyString(), anyString()))
                .thenReturn(MessagingGateway.DeliveryStatus.LOCAL_ONLY);
        when(whatsAppContext.sendImage(anyString(), anyString(), anyString()))
                .thenReturn(MessagingGateway.DeliveryStatus.LOCAL_ONLY);
        when(whatsAppContext.sendReaction(anyString(), anyString(), anyString()))
                .thenReturn(MessagingGateway.DeliveryStatus.LOCAL_ONLY);
    }
}
