package comp3011.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class WhisperClient {
    private final RestClient restClient;
    private final Path keyFile;

    public WhisperClient(
            RestClient.Builder builder,
            @Value("${openai.transcription-url}") String transcriptionUrl,
            @Value("${openai.key-file}") String keyFile) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = builder.baseUrl(transcriptionUrl).requestFactory(requestFactory).build();
        this.keyFile = Path.of(keyFile);
    }

    public Transcription transcribe(byte[] wavAudio) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(wavAudio) {
            @Override
            public String getFilename() {
                return "chunk.wav";
            }
        });
        form.add("model", "whisper-1");
        form.add("response_format", "verbose_json");
        form.add("timestamp_granularities[]", "word");
        form.add("timestamp_granularities[]", "segment");

        return restClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + readKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Transcription.class);
    }

    private String readKey() {
        for (Path candidate : List.of(keyFile, Path.of("key.txt"))) {
            if (Files.isReadable(candidate)) {
                try {
                    String key = Files.readString(candidate).trim();
                    if (!key.isEmpty()) {
                        return key;
                    }
                } catch (IOException e) {
                }
            }
        }
        String fromEnvironment = System.getenv("OPENAI_API_KEY");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        throw new IllegalStateException("No API key found in " + keyFile + ", ./key.txt or OPENAI_API_KEY");
    }
}