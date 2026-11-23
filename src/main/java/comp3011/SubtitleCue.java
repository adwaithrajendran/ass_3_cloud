package comp3011;

import java.util.List;

public record SubtitleCue(long startUs, long endUs, List<String> lines) {

    public SubtitleCue {
        if (endUs <= startUs) {
            throw new IllegalArgumentException("Subtitle cue must end after it starts");
        }
        lines = List.copyOf(lines);
    }

    public boolean covers(long timestampUs) {
        return timestampUs >= startUs && timestampUs < endUs;
    }
}