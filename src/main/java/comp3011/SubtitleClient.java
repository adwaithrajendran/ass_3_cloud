package comp3011;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SubtitleClient {
    private static final URI SUBTITLES_ENDPOINT = URI.create("http://localhost:8080/api/v1/subtitles");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Result(List<SubtitleCue> cues, boolean cached) {
    }

    private record ServerCue(double start, double end, List<String> lines) {
    }

    private record ServerResponse(List<ServerCue> cues, boolean cached) {
    }

    public Result fetch(byte[] wavAudio, String mediaId, double startSeconds) throws IOException, InterruptedException {
        String boundary = "comp3011-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(SUBTITLES_ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, wavAudio, mediaId, startSeconds)))
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("subtitle server returned HTTP " + response.statusCode());
        }

        ServerResponse body = mapper.readValue(response.body(), ServerResponse.class);
        List<SubtitleCue> cues = body.cues() == null ? List.of() : body.cues().stream()
                .filter(cue -> cue.end() > cue.start() && cue.lines() != null && !cue.lines().isEmpty())
                .map(cue -> new SubtitleCue(
                        Math.round(cue.start() * 1_000_000),
                        Math.round(cue.end() * 1_000_000),
                        cue.lines()))
                .toList();
        return new Result(cues, body.cached());
    }

    private static byte[] multipartBody(String boundary, byte[] wavAudio, String mediaId, double startSeconds) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "mediaId", mediaId);
        writeField(body, boundary, "startSeconds", Double.toString(startSeconds));
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(wavAudio);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}