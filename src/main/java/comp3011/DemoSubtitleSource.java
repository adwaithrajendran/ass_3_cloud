package comp3011;

import java.util.ArrayList;
import java.util.List;

public final class DemoSubtitleSource {
    private static final long CUE_SPACING_US = 4_000_000L;
    private static final long CUE_LENGTH_US = 3_000_000L;
    private static final long DEMO_LENGTH_US = 10 * 60 * 1_000_000L;

    private DemoSubtitleSource() {
    }

    public static void populate(SubtitleTrack track) {
        List<SubtitleCue> cues = new ArrayList<>();
        int index = 0;
        for (long startUs = 0; startUs < DEMO_LENGTH_US; startUs += CUE_SPACING_US, index++) {
            long seconds = startUs / 1_000_000L;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);
            List<String> lines = index % 2 == 0
                    ? List.of("Demo subtitle at " + time + ".")
                    : List.of("Demo subtitle at " + time + ", now showing",
                            "a second line to test the layout.");
            cues.add(new SubtitleCue(startUs, startUs + CUE_LENGTH_US, lines));
        }
        track.addCues(cues);
    }
}