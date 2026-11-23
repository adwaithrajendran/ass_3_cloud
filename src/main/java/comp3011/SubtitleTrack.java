package comp3011;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public class SubtitleTrack {
    private final ConcurrentSkipListMap<Long, SubtitleCue> cuesByStart = new ConcurrentSkipListMap<>();
    private volatile boolean enabled;

    public SubtitleTrack(boolean initiallyEnabled) {
        this.enabled = initiallyEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    public void addCues(Collection<SubtitleCue> cues) {
        for (SubtitleCue cue : cues) {
            cuesByStart.put(cue.startUs(), cue);
        }
    }

    public void clear() {
        cuesByStart.clear();
    }

    public SubtitleCue cueAt(long timestampUs) {
        Map.Entry<Long, SubtitleCue> entry = cuesByStart.floorEntry(timestampUs);
        if (entry == null) {
            return null;
        }
        SubtitleCue cue = entry.getValue();
        return cue.covers(timestampUs) ? cue : null;
    }
}