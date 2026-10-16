package com.fruver.infrastructure.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fruver.application.port.SpeechToTextService;
import com.fruver.config.AppProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** Proveedor de transcripcion intercambiable: el resto del sistema solo conoce SpeechToTextService. */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('openai') && !'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class OpenAiSpeechToText implements SpeechToTextService {

    private final RestClient http;
    private final String model;

    public OpenAiSpeechToText(RestClient.Builder builder, AppProperties props) {
        this.http = builder.baseUrl(props.stt().baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.stt().apiKey()).build();
        this.model = props.stt().model();
    }

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        String filename = "audio." + LocalAudioStorage.extension(mimeType);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(audio) {
            @Override public String getFilename() { return filename; }
        });
        form.add("model", model);
        form.add("language", "es");
        form.add("prompt", "Pedido de frutas y verduras en Colombia: libras, kilos, unidades, papa criolla, cebolla cabezona.");
        JsonNode json = http.post().uri("/v1/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(JsonNode.class);
        return json == null ? "" : json.path("text").asText("");
    }
}
