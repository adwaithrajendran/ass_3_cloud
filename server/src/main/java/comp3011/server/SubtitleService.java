package comp3011.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class SubtitleService {
    private static final TypeReference<List<SubtitleLayout.Cue>> CUE_LIST = new TypeReference<>() {
    };

    private final WhisperClient whisperClient;
    private final ObjectMapper objectMapper;
    private final Path cacheDirectory;
    private final SubtitleLayout layout = new SubtitleLayout();
    private final Map<String, List<SubtitleLayout.Cue>> memoryCache = new ConcurrentHashMap<>();
    private final DoubleAdder usageSeconds = new DoubleAdder();

    public SubtitleService(
            WhisperClient whisperClient,
            ObjectMapper objectMapper,
            @Value("${subtitles.cache-dir}") String cacheDirectory) throws IOException {
        this.whisperClient = whisperClient;
        this.objectMapper = objectMapper;
        this.cacheDirectory = Path.of(cacheDirectory);
        Files.createDirectories(this.cacheDirectory);
    }

    public Result subtitles(String chunkKey, byte[] wavAudio, double startSeconds) {
        List<SubtitleLayout.Cue> cached = memoryCache.computeIfAbsent(chunkKey, this::readFromDisk);
        if (cached != null) {
            return new Result(cached, true);
        }

        Transcription transcription = whisperClient.transcribe(wavAudio);
        usageSeconds.add(transcription.billedSeconds());
        List<SubtitleLayout.Cue> cues = layout.layout(transcription, startSeconds);
        memoryCache.put(chunkKey, cues);
        writeToDisk(chunkKey, cues);
        return new Result(cues, false);
    }

    public double totalUsageSeconds() {
        return usageSeconds.sum();
    }

    private List<SubtitleLayout.Cue> readFromDisk(String chunkKey) {
        Path file = cacheFile(chunkKey);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), CUE_LIST);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeToDisk(String chunkKey, List<SubtitleLayout.Cue> cues) {
        try {
            objectMapper.writeValue(cacheFile(chunkKey).toFile(), cues);
        } catch (IOException e) {
            System.err.println("Could not write subtitle cache for " + chunkKey + ": " + e.getMessage());
        }
    }

    private Path cacheFile(String chunkKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(chunkKey.getBytes(StandardCharsets.UTF_8));
            return cacheDirectory.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Result(List<SubtitleLayout.Cue> cues, boolean cached) {
    }
}