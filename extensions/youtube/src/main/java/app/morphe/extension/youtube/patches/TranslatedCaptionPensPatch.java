/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3837
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

@SuppressWarnings("unused")
public class TranslatedCaptionPensPatch {

    /** Interface to use obfuscated methods. */
    public interface CaptionRequestInterface {
        // Method is added during patching.

        /**
         * @return The requested object. A caption track if this is a caption request.
         */
        @Nullable
        Object patch_getCaptionTrack();
    }

    /**
     * Pen id that is never declared by a timed text document. Looking it up returns the default
     * pen, which uses the user caption style.
     */
    private static final int DEFAULT_PEN_ID = Integer.MIN_VALUE;

    /** Immutable identity for the track being parsed on each decoder thread. */
    private record ParsedTrack(boolean translated, boolean automatic, boolean uploaded, boolean authored) {}

    private static final ThreadLocal<ParsedTrack> parseState =
            ThreadLocal.withInitial(() -> new ParsedTrack(false, false, false, false));
    private static final ThreadLocal<Set<Integer>> visibleTranslatedPens = new ThreadLocal<>();

    static boolean isOriginalStyleSource() {
        ParsedTrack track = parseState.get();
        return !track.translated() && !track.automatic();
    }

    /** Opt-in injection point for the experimental styled-translation patch. */
    public static boolean preserveTranslatedStyles() {
        return false;
    }

    /**
     * Injection point.
     *
     * <p>Called before a request response or a cached caption file is parsed.
     *
     * @param request Caption request, or any other request handled by the same code.
     */
    public static void setParsedRequest(@Nullable Object request) {
        visibleTranslatedPens.remove();
        boolean translated = false;
        boolean automatic = false;
        boolean uploaded = false;
        boolean authored = false;
        Object parsedTrack = null;
        try {
            if (request instanceof CaptionRequestInterface captionRequest
                    && captionRequest.patch_getCaptionTrack()
                            instanceof PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
                parsedTrack = track;
                String vssId = track.patch_getVssId();
                // Auto-translated vss ids are "t" + source vss id + "." + language code.
                translated = vssId.startsWith("t");
                if (track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface autoTrack) {
                    String url = autoTrack.patch_getUrl();
                    automatic = AutoTranslateCaptionsPatch.isAutomaticCaption(vssId, url);
                    uploaded =
                            translated
                                    && AutoTranslateCaptionsPatch.isUploadedTranslation(
                                            autoTrack.patch_getLanguageCode(), vssId, url);
                }
                authored = vssId.startsWith(".") && !automatic;
            }
        } catch (RuntimeException | LinkageError ex) {
            Logger.printException(() -> "setParsedRequest failure", ex);
        }
        // A failed native getter must not leave the previous track state enabled.
        parseState.set(new ParsedTrack(translated, automatic, uploaded, authored));
        CaptionTracks.setTrack(parsedTrack);
        StableCaptionBlocks.setAutomaticTrack(automatic);
    }

    /**
     * Injection point.
     *
     * <p>Called before a caption track file is decoded.
     */
    public static InputStream repairInput(@Nullable InputStream input) throws IOException {
        return repairInput(input, preserveTranslatedStyles());
    }

    static InputStream repairInput(@Nullable InputStream input, boolean stylesEnabled)
            throws IOException {
        return repairInput(input, stylesEnabled, NativeCaptionRuby.enabled());
    }

    static InputStream repairInput(
            @Nullable InputStream input, boolean stylesEnabled, boolean nativeStyles)
            throws IOException {
        visibleTranslatedPens.remove();
        ParsedTrack track = parseState.get();
        boolean repair = track.translated() && track.automatic();
        boolean styles = stylesEnabled && track.uploaded();
        boolean ruby = nativeStyles;
        if (input == null
                || (!repair
                        && !styles
                        && !ruby
                        && !StableCaptionBlocks.isCapturing()
                        && !CaptionTracks.observing())) return input;
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(input, CaptionXml.MAX_BYTES);
        if (!copy.complete) return copy.input;
        Document document = CaptionXml.read(copy.bytes);
        byte[] styled = copy.bytes;
        if (styles || nativeStyles) {
            try {
                var prepared = NativeCaptionSourceInput.prepare(
                        styled, document, nativeStyles && track.authored(), ruby);
                styled = prepared.bytes();
                document = prepared.document();
                if (styles)
                    visibleTranslatedPens.set(TranslatedCaptionStylePolicy.visiblePens(document));
            } catch (IOException | RuntimeException exception) {
                // Keep the smaller ruby-only adaptation if the broader pass cannot finish.
                if (ruby) {
                    try {
                        var prepared = NativeCaptionRubyInput.prepare(styled, document);
                        styled = prepared.bytes();
                        document = prepared.document();
                    } catch (IOException | RuntimeException rubyException) {
                        Logger.printInfo(
                                () -> "Native ruby preparation unavailable", rubyException);
                    }
                }
                // Keep native decoding available if optional style preparation fails.
                Logger.printInfo(() -> "Native caption style preparation unavailable");
            }
        }
        // Capture the source captions before the repair removes events from the document.
        StableCaptionBlocks.capture(document);
        byte[] repaired = repair ? TranslatedRollingCaptionPolicy.repair(styled, document) : styled;
        if (repaired == copy.bytes) return copy.input;
        return new FilterInputStream(new ByteArrayInputStream(repaired)) {
            private boolean closed;

            @Override
            public synchronized void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    super.close();
                } finally {
                    input.close();
                }
            }
        };
    }

    /**
     * Injection point.
     *
     * <p>Auto-translated tracks keep the pen ids of the original track, but the translated text
     * does not line up with the original styled segments. Channels that use an invisible pen for
     * padding characters can end up with the entire translated line using that pen, which shows
     * only the caption background.
     *
     * <p>The opt-in style path retains only the visible pens collected from this verified uploaded
     * translation; all other translated pens still use the default.
     *
     * @param penId Pen id of a timed text element.
     * @return The pen id to use.
     */
    public static int getPenId(int penId) {
        if (!parseState.get().translated()) return penId;
        Set<Integer> visible = visibleTranslatedPens.get();
        return visible != null && visible.contains(penId) ? penId : DEFAULT_PEN_ID;
    }
}
