/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class NativeCaptionPairTest {
    @Test public void singleTranslatedOutputKeepsAllSourceWindowsWithoutSecondLanguage() {
        var source = track(List.of(new Cue(7, "First", null), new Cue(8, "Second", null)),
                List.of(new Cue(8, "Second", null)));
        var policy = StableCaptionBlockPolicy.fromNative(source, () -> true);
        var state = new StableCaptionBlocks.State(false);
        state.nativeSource = true;
        state.translation = new CaptionSession(policy.sourceBlocks("en"), "en", "tr", null,
                (from, to, texts) -> texts.stream().map(text -> "tr:" + text).toList(),
                Runnable::run, () -> {}, () -> {});
        var pair = NativeCaptionPair.forSource(source, state);
        assertNotNull(pair);
        state.nativePair = pair;
        state.translation.demand(100);
        var captions = pair.at(100, state);
        assertEquals(2, captions.size());
        for (Object item : captions) {
            Cue cue = (Cue) item;
            assertFalse(cue.frame().secondaryNativeCaption());
            assertFalse(cue.frame().usesDefaultWindow());
            assertEquals("tr:" + cue.text(), pair.fallback(state.translation.snapshot(100), false,
                    cue.frame().sourceWindowId));
        }
        assertEquals(1, pair.at(1500, state).size());
        state.translation.invalidate();
        assertTrue(pair.at(100, state).isEmpty());
    }

    @Test public void bothProviderLanguagesKeepAllNativeWindowsWithoutAnOverlay() {
        var source=track(List.of(new Cue(7,"First",null),new Cue(8,"Second",null)),List.of(new Cue(8,"Second",null)));
        var policy=StableCaptionBlockPolicy.fromNative(source,()->true);
        var state=new StableCaptionBlocks.State(false);state.nativeSource=true;
        state.translation=new CaptionSession(policy.sourceBlocks("en"),"en","tr","de",
                (from,to,texts)->texts.stream().map(t->to+":"+t).toList(),Runnable::run,()->{},()->{});
        var pair=NativeCaptionPair.forSource(source,state);assertNotNull(pair);state.nativePair=pair;
        state.translation.demand(0);var snapshot=state.translation.snapshot(100,true);
        var cues=pair.at(100,state);assertEquals(4,cues.size());
        var ids=new java.util.HashSet<Integer>();
        for(Object item:cues) {
            Cue cue=(Cue)item;assertFalse(cue.frame().nativeStyle);assertTrue(ids.add(cue.id()));
            assertFalse(cue.frame().usesDefaultWindow());
            String expected=(cue.frame().secondaryNativeCaption()?"de:":"tr:")+cue.text();
            assertEquals(expected,pair.fallback(snapshot,cue.frame().secondaryNativeCaption(),cue.frame().sourceWindowId));
        }
        assertEquals(2,pair.at(1500,state).size());
        state.translation.invalidate();assertTrue(pair.at(100,state).isEmpty());
    }
    @Test public void providerFallbackKeepsEveryWindowAndItsOwnTranslation() {
        var source=track(List.of(new Cue(7,"First",null),new Cue(8,"Second",null)),List.of(new Cue(8,"Second",null)));
        var policy=StableCaptionBlockPolicy.fromNative(source,()->true);
        var state=new StableCaptionBlocks.State(false);state.nativeSource=true;
        state.translation=new CaptionSession(policy.sourceBlocks("en"),"en","en","tr",
                (from,to,texts)->texts.stream().map(t->"tr:"+t).toList(),Runnable::run,()->{},()->{});
        var pair=NativeCaptionPair.forSource(source,state);state.nativePair=pair;
        state.translation.demand(0);
        var early=pair.at(100,state);assertEquals(4,early.size());
        Cue first=(Cue)early.get(2),second=(Cue)early.get(3);
        assertNotEquals(first.id(),second.id());assertNotEquals(first.frame(),second.frame());
        assertEquals(Integer.valueOf(7),first.frame().sourceWindowId);
        assertEquals(Integer.valueOf(8),second.frame().sourceWindowId);
        assertTrue(first.frame().usesBlockLayout());assertFalse(first.frame().usesDefaultWindow());
        assertEquals("tr:First",pair.fallback(state.translation.snapshot(100,true),true,first.frame().sourceWindowId));
        assertEquals("tr:Second",pair.fallback(state.translation.snapshot(100,true),true,second.frame().sourceWindowId));
        var late=pair.at(1500,state);assertEquals(2,late.size());
        assertEquals(second.id(),((Cue)late.get(1)).id());
        state.translation.invalidate();assertEquals(2,pair.at(100,state).size());
    }
    private record Cue(int id, CharSequence text, StableCaptionBlocks.Frame frame)
            implements StableCaptionBlocks.Cue {
        public boolean patch_isCaptionVisible() { return true; }
        public int patch_getCaptionId() { return id; }
        public CharSequence patch_getOriginalCaptionVisible() { return text; }
        public CharSequence patch_getOriginalCaptionFull() { return text; }
        public StableCaptionBlocks.Frame patch_getBlockFrame() { return frame; }
        public Object patch_copyBlockFrame(StableCaptionBlocks.Frame f) {
            return new Cue(f.captionId(id), text, f);
        }
    }
    private StableCaptionBlocks.Timeline track(List<?> early, List<?> late) {
        return new StableCaptionBlocks.Timeline() {
            public List<?> patch_getOriginalCaptions(long t) { return t < 1000 ? early : late; }
            public StableCaptionBlocks.State patch_getBlockState() { return null; }
            public void patch_setBlockState(StableCaptionBlocks.State s) { throw new UnsupportedOperationException(); }
            public StableCaptionBlocks.Timeline patch_mergeWith(StableCaptionBlocks.Timeline t) { throw new UnsupportedOperationException(); }
            public List<Long> patch_getCaptionEventTimes() { return List.of(0L,1000L,2000L); }
        };
    }
    @Test
    public void sourcePairBelongsOnlyToItsActiveTranslationSession() {
        var timeline = track(List.of(new Cue(0, "source", null)), List.of());
        var state = new StableCaptionBlocks.State(false);
        state.nativeSource = true;
        state.translation =
                new CaptionSession(
                        List.of(new StableCaptionBlockPolicy.Block(0, 2000, "source", true)),
                        "en",
                        "en",
                        "tr",
                        (from, to, texts) -> texts,
                        Runnable::run,
                        () -> {},
                        () -> {});
        var pair = NativeCaptionPair.forSource(timeline, state);
        assertNotNull(pair);
        assertTrue(pair.isSourceFor(state.translation));
        assertFalse(
                new NativeCaptionPair(new UploadedCaptionPair(timeline, null), null)
                        .isSourceFor(state.translation));
        assertFalse(pair.isSourceFor(null));
        assertFalse(pair.isSourceFor(fallback(false, true)));
        state.translation.invalidate();
        assertFalse(pair.isSourceFor(state.translation));
    }

    @Test
    public void nativeSourceKeepsEveryStyledWindowWhenOnlySecondaryNeedsTranslation() {
        CharSequence style = new StringBuilder("mixed styled primary");
        var timeline =
                track(
                        List.of(new Cue(7, style, null), new Cue(8, style, null)),
                        List.of(new Cue(8, style, null)));
        var state = new StableCaptionBlocks.State(false);
        state.nativeSource = true;
        state.translation =
                new CaptionSession(
                        List.of(new StableCaptionBlockPolicy.Block(0, 2000, "source", true)),
                        "en",
                        "en",
                        "tr",
                        (from, to, texts) -> texts.stream().map(t -> "tr:" + t).toList(),
                        Runnable::run,
                        () -> {},
                        () -> {});
        var pair = NativeCaptionPair.forSource(timeline, state);
        assertNotNull(pair);
        state.nativePair = pair;
        var cues = pair.at(100, state);
        assertEquals(3, cues.size());
        assertSame(style, ((Cue) cues.get(0)).text());
        assertSame(style, ((Cue) cues.get(1)).text());
        assertTrue(((Cue) cues.get(0)).frame().nativeStyle);
        assertTrue(((Cue) cues.get(1)).frame().nativeStyle);
        assertFalse(((Cue) cues.get(2)).frame().nativeStyle);
        assertTrue(((Cue) cues.get(2)).frame().secondaryNativeCaption());
        assertNotEquals(((Cue) cues.get(0)).id(), ((Cue) cues.get(2)).id());
        assertSame(state.translation, pair.sourceSession);
        state.translation.demand(100);
        var snapshot = state.translation.snapshot(100, true);
        assertEquals("", pair.fallback(snapshot, false));
        assertEquals("tr:source", pair.fallback(snapshot, true));
        assertEquals(2, pair.at(1500, state).size());
        assertSame(style, ((Cue) timeline.patch_getOriginalCaptions(100).get(0)).text());
    }

    @Test
    public void sourcePairRequiresAnActiveUntranslatedNativePrimary() {
        var timeline = track(List.of(new Cue(0, "source", null)), List.of());
        var state = new StableCaptionBlocks.State(false);
        assertNull(NativeCaptionPair.forSource(timeline, state));
        state.nativeSource = true;
        state.translation = fallback(true, true);
        assertNull(NativeCaptionPair.forSource(timeline, state));
        state.translation =
                new CaptionSession(
                        List.of(new StableCaptionBlockPolicy.Block(0, 2000, "source", true)),
                        "en",
                        "en",
                        "tr",
                        (from, to, texts) -> texts,
                        Runnable::run,
                        () -> {},
                        () -> {});
        state.translation.invalidate();
        assertNull(NativeCaptionPair.forSource(timeline, state));
        state.translation =
                new CaptionSession(
                        List.of(new StableCaptionBlockPolicy.Block(0, 2000, "source", true)),
                        "en",
                        "en",
                        null,
                        (from, to, texts) -> texts,
                        Runnable::run,
                        () -> {},
                        () -> {});
        assertNull(NativeCaptionPair.forSource(timeline, state));
        state.translation =
                new CaptionSession(
                        List.of(new StableCaptionBlockPolicy.Block(0, 2000, "source", true)),
                        "en",
                        "en",
                        "tr",
                        (from, to, texts) -> texts,
                        Runnable::run,
                        () -> {},
                        () -> {});
        state.nativeSource = false;
        assertNull(NativeCaptionPair.forSource(timeline, state));
    }

    @Test public void preservesAllEffectLayersAndAssignsStableDistinctIds() {
        CharSequence style = new StringBuilder("styled");
        var first = track(List.of(new Cue(0,style,null),new Cue(1,style,null)),List.of(new Cue(1,style,null)));
        var second = track(List.of(new Cue(0,"second",null)),List.of(new Cue(0,"later",null)));
        var pair = new NativeCaptionPair(new UploadedCaptionPair(first,second),null);
        var state = new StableCaptionBlocks.State(false); state.nativePair = pair;
        var early = pair.at(0,state); var late = pair.at(1500,state);
        assertEquals(3,early.size());
        Cue a=(Cue)early.get(0), b=(Cue)early.get(1), c=(Cue)early.get(2);
        assertSame(style,a.text()); assertSame(style,b.text());
        assertNotEquals(a.id(),b.id()); assertNotEquals(a.id(),c.id()); assertNotEquals(b.id(),c.id());
        assertEquals(b.id(),((Cue)late.get(0)).id()); assertEquals(c.id(),((Cue)late.get(1)).id());
        assertFalse(a.frame().usesBlockLayout()); assertFalse(b.frame().usesBlockLayout());
        assertFalse(a.frame().secondaryNativeCaption()); assertTrue(c.frame().secondaryNativeCaption());
        assertEquals(0,((Cue)first.patch_getOriginalCaptions(0).get(0)).id());
        assertNull(((Cue)first.patch_getOriginalCaptions(0).get(0)).frame());
    }
    @Test public void nativeGapsDoNotInsertFallbackOrDropOtherLanguage() {
        var first=track(List.of(new Cue(99,"",null)),List.of());
        var second=track(List.of(new Cue(-1,"second",null)),List.of(new Cue(-1,"still second",null)));
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,second),null);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;
        assertEquals(2,pair.at(0,state).size()); assertEquals(1,pair.at(1500,state).size());
    }
    private CaptionSession fallback(boolean primary, boolean secondary) {
        return new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0,2000,"source",true)),
                "en","tr","de",(from,to,texts)->texts.stream().map(text->to+":"+text).toList(),
                Runnable::run,()->{},()->{},primary,secondary);
    }
    @Test public void missingSecondaryGetsOneDefaultStyledWindowWithoutFlatteningPrimary() {
        CharSequence nativeStyle=new StringBuilder("native primary");
        var first=track(List.of(new Cue(0,nativeStyle,null)),List.of(new Cue(0,nativeStyle,null)));
        var source=track(List.of(new Cue(0,"source",null)),List.of(new Cue(0,"source",null)));
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(false,true);
        var cues=pair.at(100,state);assertEquals(2,cues.size());
        Cue nativeCue=(Cue)cues.get(0),translated=(Cue)cues.get(1);
        assertSame(nativeStyle,nativeCue.text());assertTrue(nativeCue.frame().nativeStyle);
        assertFalse(translated.frame().nativeStyle);assertTrue(translated.frame().secondaryNativeCaption());
        assertNotEquals(nativeCue.id(),translated.id());assertNotEquals(nativeCue.frame(),translated.frame());
        state.translation.demand(100);var snapshot=state.translation.snapshot(100,true);
        assertEquals("",pair.fallback(snapshot,false));assertEquals("de:source",pair.fallback(snapshot,true));
    }
    @Test public void missingPrimaryUsesPrimaryFallbackAndPreservesNativeSecondary() {
        var second=track(List.of(new Cue(0,"native second",null)),List.of());
        var source=track(List.of(new Cue(0,"source",null)),List.of());
        var pair=new NativeCaptionPair(new UploadedCaptionPair(null,second),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(true,false);
        var cues=pair.at(100,state);assertEquals(2,cues.size());
        Cue nativeCue=(Cue)cues.get(0),translated=(Cue)cues.get(1);
        assertTrue(nativeCue.frame().secondaryNativeCaption());assertFalse(translated.frame().secondaryNativeCaption());
        assertFalse(translated.frame().nativeStyle);assertNotEquals(nativeCue.id(),translated.id());
        state.translation.demand(100);var snapshot=state.translation.snapshot(100,true);
        assertEquals("tr:source",pair.fallback(snapshot,false));assertEquals("",pair.fallback(snapshot,true));
    }
    @Test public void expiredFallbackOrSourceGapDoesNotRemoveNativeLanguage() {
        var first=track(List.of(new Cue(0,"native",null)),List.of(new Cue(0,"native",null)));
        var source=track(List.of(new Cue(0,"source",null)),List.of());
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(false,true);
        assertEquals(1,pair.at(1500,state).size());state.translation.invalidate();assertEquals(1,pair.at(100,state).size());
    }
    @Test public void persistentTemplateChangesItsFrameAtSourceBlockBoundariesButKeepsWindowId() {
        var first=track(List.of(new Cue(0,"native",null)),List.of(new Cue(0,"native",null)));
        var source=track(List.of(new Cue(0,"persistent template",null)),List.of(new Cue(0,"persistent template",null)));
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;
        state.translation=new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0,1000,"a",true),
                new StableCaptionBlockPolicy.Block(1000,2000,"b",true)),"en","tr","de",
                (from,to,texts)->texts,Runnable::run,()->{},()->{},false,true);
        Cue a=(Cue)pair.at(100,state).get(1), b=(Cue)pair.at(1500,state).get(1);
        assertEquals(a.id(),b.id());assertNotEquals(a.frame(),b.frame());
        assertEquals(0,a.frame().key);assertEquals(1000,b.frame().key);
    }
    @Test public void hiddenSourceWindowDoesNotBecomeAVisibleFallbackTemplate() {
        StableCaptionBlocks.Cue hidden=new StableCaptionBlocks.Cue() {
            public boolean patch_isCaptionVisible(){return false;}
            public int patch_getCaptionId(){return 2;}
            public CharSequence patch_getOriginalCaptionVisible(){return "hidden";}
            public CharSequence patch_getOriginalCaptionFull(){return "hidden";}
            public StableCaptionBlocks.Frame patch_getBlockFrame(){return null;}
            public Object patch_copyBlockFrame(StableCaptionBlocks.Frame frame){throw new AssertionError("hidden window resurrected");}
        };
        var first=track(List.of(new Cue(0,"native",null)),List.of());
        var source=track(List.of(hidden,new Cue(3,"visible",null)),List.of());
        var pair=new NativeCaptionPair(new UploadedCaptionPair(first,null),source);
        var state=new StableCaptionBlocks.State(false);state.nativePair=pair;state.translation=fallback(false,true);
        var cues=pair.at(100,state);assertEquals(2,cues.size());assertEquals("visible",((Cue)cues.get(1)).text());
    }
}
