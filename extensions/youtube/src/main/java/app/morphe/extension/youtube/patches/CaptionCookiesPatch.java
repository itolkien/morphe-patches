package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class CaptionCookiesPatch {
    private static final boolean SET_CAPTION_COOKIES = Settings.SET_CAPTION_COOKIES.get();
    private static final String CAPTION_COOKIES = Settings.CAPTION_COOKIES.get();
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    /** Injection point. Cookie authorization belongs to this request's URL only. */
    public static boolean shouldSetCookies(String url) {
        return SET_CAPTION_COOKIES && !CAPTION_COOKIES.isEmpty()
                && AutoTranslateCaptionsPatch.captionParameters(url) != null;
    }

    /**
     * Injection point.
     */
    public static String getCookies() {
        return CAPTION_COOKIES;
    }

    /**
     * Injection point.
     */
    public static String getUserAgent() {
        return USER_AGENT;
    }
}
