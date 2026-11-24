package comp3011;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

public class SubtitleFetcher {
    private static final long CHUNK_LENGTH_US = 10_000_000L;
    private static final int LOOKAHEAD_CHUNKS = 3;
    private static final long IDLE_WAIT_MS = 200;
    private static final long FIRST_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 4_000;
    private static final int FINGERPRINT_BYTES = 1 << 20;

    private final File mediaFile;
    private final SubtitleTrack track;
    private final SubtitleClient client;
    private final Set<Long> completedChunks = new HashSet<>();
    private final Thread worker;
    private volatile boolean running;
    private AudioChunkExtractor extractor;
    private String mediaId;
    private long mediaDurationUs = -1;

    public SubtitleFetcher(File mediaFile, SubtitleTrack track, SubtitleClient client) {
        this.mediaFile = mediaFile;
        this.track = track;
        this.client = client;
        this.worker = Thread.ofPlatform().name("subtitle-fetcher").daemon(true).unstarted(this::run);
    }

    public void start() {
        running = true;
        worker.start();
    }

    public void stop() {
        running = false;
        worker.interrupt();
    }

    private void run() {
        long retryMs = FIRST_RETRY_MS;
        try {
            while (running) {
                Long chunk = track.isEnabled() ? nextChunkToFetch() : null;
                if (chunk == null) {
                    Thread.sleep(IDLE_WAIT_MS);
                    continue;
                }
                try {
                    fetchChunk(chunk);
                    retryMs = FIRST_RETRY_MS;
                } catch (IOException | RuntimeException e) {
                    System.out.printf("Subtitles: %s unavailable (%s), retrying in %ds%n",
                            describe(chunk), e.getMessage(), retryMs / 1000);
                    closeExtractor();
                    Thread.sleep(retryMs);
                    retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeExtractor();
        }
    }

    private Long nextChunkToFetch() {
        long current = Math.max(0, track.playheadUs()) / CHUNK_LENGTH_US;
        for (long chunk = current; chunk <= current + LOOKAHEAD_CHUNKS; chunk++) {
            if (mediaDurationUs > 0 && chunk * CHUNK_LENGTH_US >= mediaDurationUs) {
                return null;
            }
            if (!completedChunks.contains(chunk)) {
                return chunk;
            }
        }
        return null;
    }

    private void fetchChunk(long chunk) throws IOException, InterruptedException {
        if (extractor == null) {
            extractor = new AudioChunkExtractor(mediaFile);
            mediaDurationUs = extractor.durationUs();
        }
        if (mediaId == null) {
            mediaId = fingerprint(mediaFile);
        }

        long startUs = chunk * CHUNK_LENGTH_US;
        byte[] wavAudio = extractor.extractWav(startUs, CHUNK_LENGTH_US);
        if (wavAudio != null) {
            SubtitleClient.Result result = client.fetch(wavAudio, mediaId, startUs / 1_000_000.0);
            track.addCues(result.cues());
            System.out.printf("Subtitles: %s -> %d cues (%s)%n",
                    describe(chunk), result.cues().size(), result.cached() ? "server cache" : "transcribed");
        }
        completedChunks.add(chunk);
    }

    private void closeExtractor() {
        if (extractor != null) {
            extractor.close();
            extractor = null;
        }
    }

    private static String describe(long chunk) {
        long startSeconds = chunk * CHUNK_LENGTH_US / 1_000_000L;
        return "chunk " + startSeconds + "-" + (startSeconds + CHUNK_LENGTH_US / 1_000_000L) + "s";
    }

    private static String fingerprint(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(file.length()).getBytes(StandardCharsets.UTF_8));
            digest.update(in.readNBytes(FINGERPRINT_BYTES));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}