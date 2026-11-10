package app.morphe.extension.youtube.patches;

/** Separate persisted CC choices; -1 inherits the existing automatic visibility policy. */
final class CaptionVisibilityPolicy {
    private int regular;
    private int shorts;

    CaptionVisibilityPolicy(int regular, int shorts) {
        this.regular = regular;
        this.shorts = shorts;
    }

    synchronized Boolean remembered(boolean isShort) {
        int value = isShort ? shorts : regular;
        return value == 1 ? Boolean.TRUE : value == 0 ? Boolean.FALSE : null;
    }

    synchronized void remember(boolean isShort, boolean enabled) {
        if (isShort) {
            shorts = enabled ? 1 : 0;
        } else {
            regular = enabled ? 1 : 0;
        }
    }
}
