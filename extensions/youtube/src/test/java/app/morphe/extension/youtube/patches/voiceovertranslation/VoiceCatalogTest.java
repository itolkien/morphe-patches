/*
 * Copyright 2026 Morphe contributors.
 * See the project NOTICE for GPLv3 Section 7 terms.
 */
package app.morphe.extension.youtube.patches.voiceovertranslation;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class VoiceCatalogTest {
    @After
    public void restoreBundledCatalog() {
        VoiceCatalog.useBundledVoices();
    }

    @Test
    public void bundledCatalogWorksBeforeNetworkDownload() {
        VoiceCatalog.useBundledVoices();
        assertNotNull(VoiceCatalog.getVoice("tr-TR-EmelNeural"));
        assertNotNull(VoiceCatalog.getVoice("tr-TR-AhmetNeural"));
        assertNotNull(VoiceCatalog.resolve("tr-TR", null));
    }

    @Test
    public void dynamicCatalogPreservesNativeLanguageAndMultilingualFiltering() {
        VoiceCatalog.setAvailableVoices(Arrays.asList(
                new VoiceCatalog.Voice(true, "tr-TR-AhmetNeural"),
                new VoiceCatalog.Voice(false, "de-DE-KatjaNeural"),
                new VoiceCatalog.Voice(false, "en-US-EmmaMultilingualNeural")));

        List<VoiceCatalog.Voice> turkish = VoiceCatalog.getVoicesForLang("tr-TR");
        List<VoiceCatalog.Voice> german = VoiceCatalog.getVoicesForLang("de-DE");
        assertNotNull(turkish);
        assertNotNull(german);
        assertEquals(2, turkish.size());
        assertEquals(2, german.size());
        assertEquals("tr-TR-AhmetNeural", turkish.get(0).id);
        assertEquals("de-DE-KatjaNeural", german.get(0).id);
        assertEquals("en-US-EmmaMultilingualNeural", turkish.get(1).id);
        assertEquals("en-US-EmmaMultilingualNeural", german.get(1).id);
    }

    @Test
    public void selectedVoiceAndLanguageFallbackUseDownloadedVoices() {
        VoiceCatalog.setAvailableVoices(Arrays.asList(
                new VoiceCatalog.Voice(true, "tr-TR-AhmetNeural"),
                new VoiceCatalog.Voice(false, "de-DE-KatjaNeural"),
                new VoiceCatalog.Voice(false, "en-US-EmmaMultilingualNeural")));
        assertEquals("tr-TR-AhmetNeural", VoiceCatalog.resolve("tr-TR", "tr-TR-AhmetNeural"));
        assertEquals("tr-TR-AhmetNeural", VoiceCatalog.resolve("tr-TR", "de-DE-KatjaNeural"));
        assertEquals("en-US-EmmaMultilingualNeural", VoiceCatalog.resolve("fr-FR", null));
        assertThrows(UnsupportedOperationException.class,
                () -> VoiceCatalog.getVoicesForLang("tr").clear());
    }

    @Test
    public void replacingSnapshotRemovesVoicesMissingFromNewCatalog() {
        VoiceCatalog.setAvailableVoices(Collections.singletonList(
                new VoiceCatalog.Voice(false, "tr-TR-EmelNeural")));
        VoiceCatalog.setAvailableVoices(Collections.singletonList(
                new VoiceCatalog.Voice(false, "en-US-EmmaMultilingualNeural")));
        assertNull(VoiceCatalog.getVoice("tr-TR-EmelNeural"));
        assertEquals("en-US-EmmaMultilingualNeural", VoiceCatalog.resolve("tr-TR", null));
    }
    @Test
    public void networkOrderDoesNotChangeEstablishedDefaults() {
        VoiceCatalog.setAvailableVoices(Arrays.asList(
                new VoiceCatalog.Voice(false, "tr-TR-EmelNeural"),
                new VoiceCatalog.Voice(true, "tr-TR-AhmetNeural")));
        assertEquals("tr-TR-AhmetNeural", VoiceCatalog.resolve("tr-TR", null));
        assertEquals("tr-TR-EmelNeural", VoiceCatalog.resolve("tr-TR", "tr-TR-EmelNeural"));
    }

    @Test
    public void emptyUpdateNeverDiscardsWorkingVoices() {
        VoiceCatalog.useBundledVoices();
        assertThrows(IllegalArgumentException.class,
                () -> VoiceCatalog.setAvailableVoices(Collections.emptyList()));
        assertNotNull(VoiceCatalog.getVoice("tr-TR-EmelNeural"));
    }

}
