package app.morphe.extension.youtube.patches.captions.model;

import java.util.List;
import java.util.Map;

/** A sampled source frame. IDs survive translation and repeated sampling. */
public record CaptionScene(List<Window> windows, List<Window> secondaryWindows) {
    public CaptionScene(List<Window> windows) {
        this(windows, List.of());
    }

    public CaptionScene {
        windows = List.copyOf(windows);
        secondaryWindows = List.copyOf(secondaryWindows);
    }

    public CaptionScene withSecondary(CaptionScene secondary) {
        return new CaptionScene(windows, secondary.windows());
    }

    public record Window(
            String id,
            long startMs,
            long endMs,
            Map<String, String> attributes,
            Map<String, String> position,
            Map<String, String> style,
            List<Run> runs) {
        public Window {
            attributes = Map.copyOf(attributes);
            position = Map.copyOf(position);
            style = Map.copyOf(style);
            runs = List.copyOf(runs);
        }
    }

    public record Run(
            String id,
            String text,
            long revealMs,
            Map<String, String> pen,
            Map<String, String> attributes,
            String paragraphId,
            long hideMs) {
        public Run(
                String id,
                String text,
                long revealMs,
                Map<String, String> pen,
                Map<String, String> attributes) {
            this(id, text, revealMs, pen, attributes, null, Long.MAX_VALUE);
        }

        public Run {
            pen = Map.copyOf(pen);
            attributes = Map.copyOf(attributes);
        }

        public Run withText(String value) {
            return new Run(id, value, revealMs, pen, attributes, paragraphId, hideMs);
        }

        public Run withEnd(long end) {
            return new Run(id, text, revealMs, pen, attributes, paragraphId, end);
        }
    }
}
