package comp3011.server;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Transcription(double duration, List<Segment> segments, List<Word> words, Usage usage) {

    public List<Segment> segments() {
        return segments == null ? List.of() : segments;
    }

    public List<Word> words() {
        return words == null ? List.of() : words;
    }

    public double billedSeconds() {
        return usage != null && usage.seconds() > 0 ? usage.seconds() : duration;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
            double start,
            double end,
            String text,
            @JsonProperty("no_speech_prob") double noSpeechProb,
            @JsonProperty("avg_logprob") double avgLogProb) {

        public boolean isSpeech() {
            return !(noSpeechProb > 0.6 && avgLogProb < -1.0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Word(String word, double start, double end) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(String type, double seconds) {
    }
}