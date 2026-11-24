package comp3011.server;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class SubtitleController {
    private final SubtitleService subtitleService;

    public SubtitleController(SubtitleService subtitleService) {
        this.subtitleService = subtitleService;
    }

    @PostMapping(value = "/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> subtitles(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("mediaId") String mediaId,
            @RequestParam("startSeconds") double startSeconds) throws IOException {
        String chunkKey = mediaId + "@" + Math.round(startSeconds * 1000);
        try {
            return ResponseEntity.ok(subtitleService.subtitles(chunkKey, audio.getBytes(), startSeconds));
        } catch (RestClientException | IllegalStateException e) {
            System.err.println("Transcription failed for " + chunkKey + ": " + e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Transcription service unavailable"));
        }
    }

    @GetMapping("/global/usage")
    public Map<String, Double> usage() {
        return Map.of("usage", subtitleService.totalUsageSeconds());
    }
}