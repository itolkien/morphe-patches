package app.morphe.extension.youtube.patches;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CaptionSessionTest {
    @Test public void evictionRemovesTextAndNativeRunsBeforeRetranslation() {
        var calls = new AtomicInteger();
        var blocks = new ArrayList<StableCaptionBlockPolicy.Block>();
        for (int i = 0; i < 65; i++) {
            String id = "group" + i, text = "source" + i, style = "style" + i + ":";
            var plan = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group(
                    id, List.of(text), null, (value, annotation) -> style + value)));
            blocks.add(new StableCaptionBlockPolicy.Block(i * 40000L, i * 40000L + 1000,
                    text, true, Map.of(i, text), Map.of(i, plan)));
        }
        CaptionTranslator provider = (from, to, texts) -> {
            int version = calls.incrementAndGet();
            return texts.stream().map(text -> "v" + version + ":" + text).toList();
        };
        var session = new CaptionSession(blocks, "en", "de", null, provider,
                Runnable::run, () -> {}, () -> {});
        for (int i = 0; i < 65; i++) session.demand(i * 40000L + 100);
        assertEquals(65, calls.get());
        var evicted = session.snapshot(100, true).windows.get(0);
        assertEquals("", evicted.primary());
        assertNull(evicted.primaryRuns());
        session.demand(100);
        var restored = session.snapshot(100, true).windows.get(0);
        assertEquals("v66:source0", restored.primary());
        assertEquals(List.of("style0:v66:source0"),
                restored.primaryRuns().render(Set.of("group0")));
        assertEquals(66, calls.get());
    }

    @Test public void scalarNativeWindowsBatchAndDeduplicateWithoutLosingTheirRenderers() {
        var calls = new java.util.ArrayList<List<String>>();
        var blocks = new java.util.ArrayList<StableCaptionBlockPolicy.Block>();
        for (int i = 0; i < 3; i++) {
            String text = i == 1 ? "two" : "one", id = "g" + i, style = "style" + i + ":";
            var plan = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group(id, List.of(text), null,
                    (value, annotation) -> style + value)));
            blocks.add(new StableCaptionBlockPolicy.Block(i * 1000, (i + 1) * 1000, text, true,
                    java.util.Map.of(i, text), java.util.Map.of(i, plan)));
        }
        CaptionTranslator provider = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) {
                calls.add(texts); return texts.stream().map(text -> "tr:" + text).toList();
            }
            public CaptionTranslator.StyledResult translateStyled(String from, String to,
                    List<app.morphe.extension.youtube.patches.captions.model.CaptionScene.Run> runs,
                    java.util.function.BooleanSupplier active) {
                throw new AssertionError("Single owned runs should share a provider batch");
            }
        };
        var session = new CaptionSession(blocks, "en", "tr", null, provider,
                Runnable::run, () -> {}, () -> {});
        session.demand(100);
        assertEquals(List.of(List.of("one", "two")), calls);
        assertEquals(List.of("style0:tr:one"), session.snapshot(100).windows.get(0)
                .primaryRuns().render(java.util.Set.of("g0")));
        assertEquals(List.of("style2:tr:one"), session.snapshot(2100).windows.get(2)
                .primaryRuns().render(java.util.Set.of("g2")));
    }

    @Test public void currentStyledWindowNotifiesBeforeFutureWindowTranslation() {
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        var blocks = new java.util.ArrayList<StableCaptionBlockPolicy.Block>();
        for (int i = 0; i < 2; i++) {
            String id = "w" + i;
            var plan = new NativeCaptionRunPlan(List.of(
                    new NativeCaptionRunPlan.Group(id + "a", List.of("one"), null, (text, annotation) -> text),
                    new NativeCaptionRunPlan.Group(id + "b", List.of("two"), null, (text, annotation) -> text)));
            blocks.add(new StableCaptionBlockPolicy.Block(i * 1000, (i + 1) * 1000, "onetwo", true,
                    java.util.Map.of(i, "onetwo"), java.util.Map.of(i, plan)));
        }
        CaptionTranslator provider = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) { return texts; }
            public CaptionTranslator.StyledResult translateStyled(String from, String to,
                    List<app.morphe.extension.youtube.patches.captions.model.CaptionScene.Run> runs,
                    java.util.function.BooleanSupplier active) {
                if (runs.get(0).id().startsWith("w1")) assertTrue(notifications.get() > 0);
                return new CaptionTranslator.StyledResult(runs.stream().map(run ->
                        new CaptionTranslator.Part(run.id(), "translated:" + run.text())).toList());
            }
        };
        var session = new CaptionSession(blocks, "en", "tr", null, provider,
                Runnable::run, notifications::incrementAndGet, () -> {});
        session.demand(100);
        assertTrue(session.snapshot(100).complete);
    }

    @Test public void successfulStyledWindowSurvivesPlainNeighborFailure() {
        var plan = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group("owned", List.of("good"),
                null, (text, annotation) -> "native:" + text)));
        var windows = new java.util.LinkedHashMap<Integer, String>();
        windows.put(7, "good"); windows.put(8, "bad");
        var block = new StableCaptionBlockPolicy.Block(0, 2000, "good bad", true,
                windows, java.util.Map.of(7, plan));
        CaptionTranslator provider = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) throws java.io.IOException {
                throw new CaptionTranslator.Failure("Unavailable", 0, false);
            }
            public CaptionTranslator.StyledResult translateStyled(String from, String to,
                    List<app.morphe.extension.youtube.patches.captions.model.CaptionScene.Run> runs,
                    java.util.function.BooleanSupplier active) {
                return new CaptionTranslator.StyledResult(List.of(new CaptionTranslator.Part("owned.0", "translated")));
            }
        };
        var session = new CaptionSession(List.of(block), "en", "tr", "en", provider,
                Runnable::run, () -> {}, () -> {});
        session.demand(100);
        assertEquals("translated", session.snapshot(100).windows.get(7).primary());
        assertEquals(List.of("native:translated"), session.snapshot(100).windows.get(7)
                .primaryRuns().render(java.util.Set.of("owned")));
    }

    @Test public void unmappedWindowCacheWaitsForSemanticTranslation() {
        var plan = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group("owned", List.of("source"),
                null, (text, annotation) -> "native:" + text)));
        var block = new StableCaptionBlockPolicy.Block(0, 2000, "source", true,
                java.util.Map.of(7, "source"), java.util.Map.of(7, plan));
        CaptionTranslator provider = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) { return texts; }
            public String cached(String from, String to, String text) { return "unmapped cache"; }
        };
        var session = new CaptionSession(List.of(block), "en", "en", "tr", provider,
                task -> {}, () -> {}, () -> {});
        assertEquals("", session.snapshot(100).windows.get(7).secondary());
        assertNull(session.snapshot(100).windows.get(7).secondaryRuns());
    }

    @Test public void failedStyledPrimaryRetainsAuthoredSourceRenderer() {
        var plan = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group("owned", List.of("source"),
                null, (text, annotation) -> "native:" + text)));
        var block = new StableCaptionBlockPolicy.Block(0, 2000, "source", true,
                java.util.Map.of(7, "source"), java.util.Map.of(7, plan));
        CaptionTranslator provider = (from, to, texts) -> {
            throw new CaptionTranslator.Failure("Unavailable", 0, false);
        };
        var session = new CaptionSession(List.of(block), "en", "tr", "de", provider,
                Runnable::run, () -> {}, () -> {});
        session.demand(100);
        var snapshot = session.snapshot(100).windows.get(7);
        assertEquals("source", snapshot.primary());
        assertNotNull(snapshot.primaryRuns());
        assertEquals(List.of("native:source"), snapshot.primaryRuns().render(java.util.Set.of("owned")));
        assertEquals("", snapshot.secondary());
    }

    @Test public void failedStructuredWindowDoesNotDiscardItsNeighbor() {
        var good = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group("good", List.of("good"),
                null, (text, annotation) -> text)));
        var bad = new NativeCaptionRunPlan(List.of(new NativeCaptionRunPlan.Group("bad", List.of("bad"),
                null, (text, annotation) -> text)));
        var block = new StableCaptionBlockPolicy.Block(0, 2000, "good bad", true,
                java.util.Map.of(7, "good", 8, "bad"), java.util.Map.of(7, good, 8, bad));
        CaptionTranslator provider = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) throws java.io.IOException {
                throw new java.io.IOException("offline");
            }
            public CaptionTranslator.StyledResult translateStyled(String from, String to,
                    List<app.morphe.extension.youtube.patches.captions.model.CaptionScene.Run> runs,
                    java.util.function.BooleanSupplier active) {
                return runs.get(0).id().equals("bad.0") ? null : new CaptionTranslator.StyledResult(
                        List.of(new CaptionTranslator.Part("good.0", "translated")));
            }
        };
        var session = new CaptionSession(List.of(block), "en", "en", "tr", provider,
                Runnable::run, () -> {}, () -> {});
        session.demand(100);
        assertEquals("translated", session.snapshot(100).windows.get(7).secondary());
        assertNotNull(session.snapshot(100).windows.get(7).secondaryRuns());
        assertEquals("", session.snapshot(100).windows.get(8).secondary());
        assertNull(session.snapshot(100).windows.get(8).secondaryRuns());
        session.invalidate();
        assertNull(session.snapshot(100));
    }

    @Test public void nativeWindowRequestsPreserveSemanticGroupsBeforePlainFallback() {
        var plan = new NativeCaptionRunPlan(List.of(
                new NativeCaptionRunPlan.Group("red", List.of("one"), null, (text, annotation) -> "red:" + text),
                new NativeCaptionRunPlan.Group("blue", List.of("two"), null, (text, annotation) -> "blue:" + text)));
        var block = new StableCaptionBlockPolicy.Block(0, 2000, "onetwo", true,
                java.util.Map.of(7, "onetwo"), java.util.Map.of(7, plan));
        var calls = new java.util.ArrayList<String>();
        CaptionTranslator provider = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) {
                calls.add("plain"); return texts;
            }
            public CaptionTranslator.StyledResult translateStyled(String from, String to,
                    List<app.morphe.extension.youtube.patches.captions.model.CaptionScene.Run> runs,
                    java.util.function.BooleanSupplier active) {
                calls.add("styled");
                return new CaptionTranslator.StyledResult(List.of(new CaptionTranslator.Part("blue.0", "iki"),
                        new CaptionTranslator.Part("red.0", "bir")));
            }
        };
        var session = new CaptionSession(List.of(block), "en", "en", "tr", provider,
                Runnable::run, () -> {}, () -> {});
        session.demand(100);
        assertEquals(List.of("styled"), calls);
        assertEquals("ikibir", session.snapshot(100).windows.get(7).secondary());
        assertNotNull(session.snapshot(100).windows.get(7).secondaryRuns());
        assertEquals(List.of("blue:iki", "red:bir"), session.snapshot(100).windows.get(7)
                .secondaryRuns().render(java.util.Set.of("red", "blue")));
    }

    @Test public void partialWindowResultDoesNotSuppressOtherWindowsOrLanguage() {
        var source=List.of(new StableCaptionBlockPolicy.Block(0,2000,"legacy",true,Map.of(7,"A",8,"B")));
        var session=new CaptionSession(source,"en","tr","de",(from,to,texts)->texts.stream()
                .map(text->to.equals("tr")&&text.equals("B")?null:to+":"+text).toList(),
                Runnable::run,()->{},()->{});
        session.demand(0);var snapshot=session.snapshot(100,true);
        assertEquals("tr:A",snapshot.windows.get(7).primary());
        assertEquals("de:A",snapshot.windows.get(7).secondary());
        assertEquals("de:B",snapshot.windows.get(8).secondary());
        assertFalse(snapshot.complete);
    }

    @Test public void denseWindowSourceUsesBoundedProviderBatches() {
        var windows=new LinkedHashMap<Integer,String>();
        for(int i=0;i<80;i++) windows.put(i,"window"+i);
        var source=List.of(new StableCaptionBlockPolicy.Block(0,2000,"legacy",true,windows));
        var session=new CaptionSession(source,"en","tr",null,(from,to,texts)->{
            assertTrue(texts.size()<=CaptionTranslator.MAX_BATCH_TEXTS);
            return texts.stream().map(text->"tr:"+text).toList();
        },Runnable::run,()->{},()->{});
        session.demand(0);var snapshot=session.snapshot(100,true);
        assertEquals(80,snapshot.windows.size());
        assertEquals("tr:window79",snapshot.windows.get(79).primary());
        assertTrue(snapshot.complete);
    }
    @Test public void nativeWindowCanReceiveItsTranslationBeforeItsOwnShortEnd() {
        Jobs jobs=new Jobs();
        var source=List.of(new StableCaptionBlockPolicy.Block(0,400,"legacy",true,Map.of(7,"Short")));
        var session=new CaptionSession(source,"en","tr",null,(from,to,texts)->texts.stream().map(t->"tr:"+t).toList(),
                jobs,()->{},()->{});
        session.demand(0);assertEquals("",session.snapshot(0).primary);
        jobs.run();assertEquals("tr:Short",session.snapshot(200).windows.get(7).primary());
        assertNull(session.snapshot(400));
    }
    @Test public void nativeWindowsAreTranslatedIndividuallyAndKeepTheirIds() {
        var source=List.of(new StableCaptionBlockPolicy.Block(0,2000,"combined legacy text",true,
                new LinkedHashMap<>(Map.of(7,"First\nline",8,"Second"))));
        List<String> requests=new ArrayList<>();
        var session=new CaptionSession(source,"en","tr",null,(from,to,texts)->{
            requests.addAll(texts);return texts.stream().map(text->to+":"+text).toList();
        },Runnable::run,()->{},()->{});
        session.demand(0);var snapshot=session.snapshot(100,true);
        assertFalse(requests.contains("combined legacy text"));
        assertEquals(Set.of(7,8),snapshot.windows.keySet());
        assertEquals("tr:First\nline",snapshot.windows.get(7).primary());
        assertEquals("tr:Second",snapshot.windows.get(8).primary());
        assertThrows(UnsupportedOperationException.class,()->snapshot.windows.clear());
    }

    @Test public void endedNativeWindowDoesNotLeakIntoTheNextSnapshot() {
        var source=List.of(new StableCaptionBlockPolicy.Block(0,1000,"first",true,Map.of(7,"A",8,"B")),
                new StableCaptionBlockPolicy.Block(1000,2000,"next",true,Map.of(8,"B")));
        var session=new CaptionSession(source,"en","tr","de",(from,to,texts)->texts.stream().map(text->to+":"+text).toList(),
                Runnable::run,()->{},()->{});
        session.demand(0);
        assertEquals(Set.of(7,8),session.snapshot(100,true).windows.keySet());
        assertEquals(Set.of(8),session.snapshot(1100,true).windows.keySet());
        session.invalidate();assertNull(session.snapshot(100,true));
    }

    private static final List<StableCaptionBlockPolicy.Block> SOURCE = List.of(
            new StableCaptionBlockPolicy.Block(0, 3000, "first source", true),
            new StableCaptionBlockPolicy.Block(4000, 7000, "second source", true),
            new StableCaptionBlockPolicy.Block(31000, 34000, "far source", true));
    static final class Jobs implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        public void execute(Runnable r) { queue.add(r); }
        void run() { while (!queue.isEmpty()) queue.remove().run(); }
    }

    @Test public void pendingTranslationDoesNotTemporarilyShowSourceLanguage() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", "tr",
                (from, to, texts) -> texts.stream().map(text -> to + ":" + text).toList(),
                jobs, () -> {}, () -> {});
        session.demand(0);
        assertEquals("", session.snapshot(100).primary);
        assertEquals("", session.snapshot(100).secondary);
        jobs.run();
        assertEquals("de:first source", session.snapshot(200).primary);
    }

    @Test public void warmTranslationsRemainVisibleWhileReplacementWorkerIsQueued() {
        Jobs jobs = new Jobs();
        CaptionTranslator cached = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) {
                fail("Snapshot must not start network work");
                return null;
            }
            public String cached(String from, String to, String text) {
                return to + ":" + text;
            }
        };
        var session = new CaptionSession(SOURCE, "en", "de", "tr", cached,
                jobs, () -> {}, () -> {});
        session.demand(0);
        var cue = session.snapshot(100);
        assertEquals("de:first source", cue.primary);
        assertEquals("tr:first source", cue.secondary);
        assertTrue(cue.complete);
    }
    @Test public void secondaryFailureCannotExposeSourceOfAnotherPendingCue() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (from, to, texts) -> {
            if (to.equals("tr")) throw new IOException("secondary unavailable");
            return texts.stream().map(text -> "de:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0);
        jobs.run();
        session.demand(31000);
        assertEquals("", session.snapshot(31000).primary);
    }

    @Test public void fastPrefetchCannotCycleThroughEvictedTranslations() {
        assertBoundedPrefetch(1500, 4);
    }
    @Test public void denseAuthoredCuesCannotCycleAtNormalSpeed() {
        assertBoundedPrefetch(300, 1);
    }
    private void assertBoundedPrefetch(int duration, float speed) {
        List<StableCaptionBlockPolicy.Block> cues = new ArrayList<>();
        for (int i = 0; i < 70; i++) cues.add(new StableCaptionBlockPolicy.Block(
                (long) i * duration, (long) (i + 1) * duration, "cue" + i, true));
        AtomicInteger calls = new AtomicInteger();
        var session = new CaptionSession(cues, "en", "de", null, (from, to, texts) -> {
            assertTrue("Prefetch must finish without revisiting evicted blocks", calls.incrementAndGet() < 30);
            return texts.stream().map(text -> "de:" + text).toList();
        }, Runnable::run, () -> {}, () -> {});
        session.demand(0, speed);
        assertTrue(session.isActive());
        assertEquals("de:cue0", session.snapshot(0).primary);
        session.demand(6L * duration, speed);
        assertEquals("de:cue69", session.snapshot(69L * duration).primary);
        assertTrue(calls.get() <= 15);
    }
    @Test public void nativeCueOutsidePlanningHintStillUsesSelectedLanguages() {
        String text = "on my phone okay so you might get this message as you can see we have a got";
        var source = List.of(new StableCaptionBlockPolicy.Block(116799, 122159, text, false));
        for (String secondary : new String[]{null, "fr"}) {
            var session = new CaptionSession(source, "en", "de", secondary,
                    (from, to, texts) -> texts.stream().map(value -> to + ":" + value).toList(),
                    Runnable::run, () -> {}, () -> fail("Valid native cue must be translated"));
            session.demand(116803);
            var cue = session.snapshot(116803);
            assertEquals("de:" + text, cue.primary);
            assertEquals(secondary == null ? "" : "fr:" + text, cue.secondary);
            assertTrue(cue.complete);
            assertEquals(116799, cue.start);
            assertEquals(122159, cue.end);
        }
    }

    @Test public void oversizedNativeCueIsSplitWithoutSkippingTranslation() {
        String text = "😀".repeat(StableCaptionBlockPolicy.MAX_BLOCK_CHARS + 1);
        List<String> sent = new ArrayList<>();
        var session = new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0, 3000, text, false)),
                "en", "de", "fr", (from, to, texts) -> {
                    sent.addAll(texts);
                    for (String value : texts) assertTrue(value.codePointCount(0, value.length()) <= 512);
                    return texts;
                }, Runnable::run, () -> {}, () -> fail("Long cue must not be skipped"));
        session.demand(0);
        assertTrue(session.snapshot(100).complete);
        assertEquals(text, session.snapshot(100).primary);
        assertEquals(text, session.snapshot(100).secondary);
        assertEquals(4, sent.size());
    }

    @Test public void outputsUseOneSourceClockAndOneBoundedDemand() {
        Jobs jobs = new Jobs(); List<String> requests = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "de", "tr",
                (from,to,texts) -> { requests.add(to+":"+texts.size()); return texts.stream().map(t -> to+":"+t).toList(); }, jobs, () -> {}, () -> {});
        session.demand(0);session.demand(0);assertEquals(1,jobs.queue.size());jobs.run();
        assertEquals(List.of("de:2","tr:2"),requests);
        var cue=session.snapshot(100);
        assertEquals(0,cue.start);assertEquals(3000,cue.end);
        assertEquals("de:first source",cue.primary);assertEquals("tr:first source",cue.secondary);
        assertSame(cue,session.snapshot(200));assertNull(session.snapshot(3500));
        session.demand(100);jobs.run();assertEquals(2,requests.size());
    }
    @Test public void latePayloadDoesNotFlashWithTooLittleReadingTime() {
        Jobs jobs=new Jobs();var session=new CaptionSession(SOURCE,"en","de","tr",
                (f,t,texts)->texts.stream().map(x->t+":"+x).toList(),jobs,()->{},()->{});
        session.demand(2000);assertEquals("",session.snapshot(2000).primary);jobs.run();
        assertEquals("",session.snapshot(2100).primary);
        assertEquals("de:second source",session.snapshot(4100).primary);
        assertEquals("de:first source",session.snapshot(500).primary);
    }
    @Test public void cancellationStopsSecondTargetAndLateDelivery() {
        Jobs jobs=new Jobs();AtomicInteger calls=new AtomicInteger(),updates=new AtomicInteger();CaptionSession[] holder=new CaptionSession[1];
        holder[0]=new CaptionSession(SOURCE,"en","de","tr",(f,t,texts)->{
            calls.incrementAndGet();holder[0].invalidate();return texts;},jobs,updates::incrementAndGet,()->fail("Cancellation is not failure"));
        holder[0].demand(0);jobs.run();assertEquals(1,calls.get());assertEquals(0,updates.get());assertNull(holder[0].snapshot(0));
    }
    @Test public void sameLanguageNeedsNoProviderAndDuplicateOutputIsNotRequested() {
        Jobs jobs=new Jobs();var session=new CaptionSession(SOURCE,"en","en","en",
                (f,t,texts)->{fail("No network for same-language output");return null;},jobs,()->{},()->{});
        session.demand(0);jobs.run();var cue=session.snapshot(100);assertEquals("first source",cue.primary);assertEquals("",cue.secondary);
    }
    @Test public void failurePreservesAvailablePrimaryAndDoesNotRetryEachFrame() {
        Jobs jobs=new Jobs();AtomicInteger failures=new AtomicInteger(),calls=new AtomicInteger();
        var session=new CaptionSession(SOURCE,"en","de","tr",(f,t,texts)->{calls.incrementAndGet();if(t.equals("tr"))throw new IOException("unavailable");return texts.stream().map(x->"de:"+x).toList();},jobs,()->{},failures::incrementAndGet);
        session.demand(0);jobs.run();var cue=session.snapshot(100);assertEquals("de:first source",cue.primary);assertEquals("",cue.secondary);
        session.demand(100);jobs.run();assertEquals(2,calls.get());assertEquals(1,failures.get());
    }
    @Test public void partialProviderResultCannotShiftCaptions() {
        Jobs jobs=new Jobs();var session=new CaptionSession(SOURCE,"en","de",null,
                (f,t,texts)->List.of("wrong count"),jobs,()->{},()->{});
        session.demand(0);jobs.run();assertEquals("first source",session.snapshot(100).primary);
        assertEquals("second source",session.snapshot(4100).primary);
    }
    @Test public void primaryFailureDoesNotSuppressAvailableSecondary() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (from, to, texts) -> {
            if (to.equals("de")) throw new IOException("unavailable");
            return texts.stream().map(text -> "tr:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        var cue = session.snapshot(100);
        assertEquals("first source", cue.primary);
        assertEquals("tr:first source", cue.secondary);
    }
    @Test public void pausedCaptionCanAcceptLateTranslationWithoutAFlash() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", null,
                (from, to, texts) -> texts.stream().map(text -> "de:" + text).toList(), jobs, () -> {}, () -> {});
        session.demand(2900);
        assertEquals("", session.snapshot(2900, true).primary);
        jobs.run();
        assertEquals("de:first source", session.snapshot(2900, true).primary);
    }
    @Test public void originalAndTranslatedOutputsShareTheClockAcrossSeekAndClear() {
        Jobs jobs = new Jobs(); List<String> targets = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "en", "de", (from, to, texts) -> {
            targets.add(to); return texts.stream().map(text -> "de:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        var cue = session.snapshot(100);
        assertEquals(List.of("de"), targets);
        assertEquals("first source", cue.primary); assertEquals("de:first source", cue.secondary);
        assertEquals(0, cue.start); assertEquals(3000, cue.end);
        assertNull(session.snapshot(3500));
        assertEquals("second source", session.snapshot(4100).primary);
        assertEquals("first source", session.snapshot(100).primary);
    }
    @Test public void singleTranslatedOutputUsesTheSameSessionWithoutASecondRequest() {
        Jobs jobs = new Jobs(); AtomicInteger requests = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "ja", null, (from, to, texts) -> {
            assertEquals("ja", to); requests.incrementAndGet();
            return texts.stream().map(text -> "訳:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run(); var cue = session.snapshot(4100);
        assertEquals(1, requests.get()); assertTrue(cue.complete);
        assertEquals(4000, cue.start); assertEquals(7000, cue.end);
        assertEquals("訳:second source", cue.primary); assertEquals("", cue.secondary);
    }
    @Test public void failedCueRetriesOnExplicitBackwardSeekButNotEveryFrame() {
        Jobs jobs = new Jobs(); AtomicInteger calls = new AtomicInteger(); boolean[] unavailable = {true};
        var session = new CaptionSession(SOURCE, "en", "de", null, (from, to, texts) -> {
            calls.incrementAndGet(); if (unavailable[0]) throw new IOException("temporary failure");
            return texts.stream().map(text -> "de:" + text).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(2000); jobs.run(); assertEquals(1, calls.get());
        session.demand(2100); jobs.run(); assertEquals(1, calls.get());
        unavailable[0] = false;
        session.demand(100); jobs.run(); assertEquals(2, calls.get());
        assertEquals("de:first source", session.snapshot(100).primary);
        session.demand(200); jobs.run(); assertEquals(2, calls.get());
    }
    @Test public void shortSourceIsStillTranslated() {
        Jobs jobs = new Jobs(); AtomicInteger requests = new AtomicInteger();
        var session = new CaptionSession(List.of(new StableCaptionBlockPolicy.Block(0, 1000, "short", true)), "en", "de", null,
                (f,t,texts) -> { requests.incrementAndGet(); return List.of("kurz"); }, jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        assertEquals(1, requests.get()); assertEquals("kurz", session.snapshot(100).primary);
    }
    @Test public void alignedPartialResultsKeepSuccessfulSiblings() {
        Jobs jobs = new Jobs();
        var session = new CaptionSession(SOURCE, "en", "de", null,
                (f,t,texts) -> Arrays.asList("eins", null), jobs, () -> {}, () -> {});
        session.demand(0); jobs.run();
        assertEquals("eins", session.snapshot(100).primary);
        assertEquals("second source", session.snapshot(4100).primary);
    }
    @Test public void primaryPublishesBeforeSecondaryFinishes() {
        Jobs jobs = new Jobs(); CaptionSession[] holder = new CaptionSession[1]; AtomicInteger updates = new AtomicInteger();
        holder[0] = new CaptionSession(SOURCE, "en", "de", "tr", (f,t,texts) -> {
            if (t.equals("tr")) { assertTrue(updates.get() > 0); assertEquals("de:first source", holder[0].snapshot(100).primary); }
            return texts.stream().map(x -> t + ":" + x).toList();
        }, jobs, updates::incrementAndGet, () -> {});
        holder[0].demand(0); jobs.run();
        assertEquals("tr:first source", holder[0].snapshot(100).secondary);
    }

    @Test public void seekDuringPrimaryPrioritizesNewCueBeforeObsoleteSecondary() {
        Jobs jobs = new Jobs();
        List<String> requests = new ArrayList<>();
        CaptionSession[] holder = new CaptionSession[1];
        holder[0] = new CaptionSession(SOURCE, "en", "de", "tr", (from, to, texts) -> {
            requests.add(to + ":" + String.join(",", texts));
            if (requests.size() == 1) holder[0].demand(31000);
            return texts.stream().map(text -> to + ":" + text).toList();
        }, jobs, () -> {}, () -> {});
        holder[0].demand(0);
        jobs.run();

        assertEquals(List.of("de:first source,second source", "de:far source", "tr:far source"), requests);
        assertEquals("de:far source", holder[0].snapshot(31000).primary);
        assertEquals("tr:far source", holder[0].snapshot(31000).secondary);
    }

    @Test public void backwardSeekDuringFailurePreservesProviderRetryAfter() {
        Jobs jobs = new Jobs();
        long[] now = {0};
        AtomicInteger calls = new AtomicInteger();
        CaptionSession[] holder = new CaptionSession[1];
        var source = List.of(new StableCaptionBlockPolicy.Block(0, 10000, "source", true));
        holder[0] = new CaptionSession(source, "en", "de", null, (from, to, texts) -> {
            if (calls.incrementAndGet() == 1) {
                holder[0].demand(100);
                throw new CaptionTranslator.Failure("limited", 60000, true);
            }
            return List.of("translated");
        }, jobs, () -> {}, () -> {}, () -> now[0]);

        holder[0].demand(2000);
        jobs.run();
        assertEquals("A seek must not discard the in-flight provider cooldown", 1, calls.get());
        now[0] = 59999;
        holder[0].demand(200);
        jobs.run();
        assertEquals(1, calls.get());
        now[0] = 60000;
        holder[0].demand(300);
        jobs.run();
        assertEquals(2, calls.get());
        assertEquals("translated", holder[0].snapshot(300).primary);
    }

    @Test public void seekDuringTerminalPrimaryFailureStillRequestsUnattemptedSecondary() {
        Jobs jobs = new Jobs();
        List<String> targets = new ArrayList<>();
        CaptionSession[] holder = new CaptionSession[1];
        var source = List.of(new StableCaptionBlockPolicy.Block(0, 10000, "source", true));
        holder[0] = new CaptionSession(source, "en", "de", "tr", (from, to, texts) -> {
            targets.add(to);
            if (to.equals("de")) {
                holder[0].demand(100);
                throw new CaptionTranslator.Failure("unsupported", 0, false);
            }
            return List.of("translated secondary");
        }, jobs, () -> {}, () -> {});

        holder[0].demand(2000);
        jobs.run();

        assertEquals(List.of("de", "tr"), targets);
        assertEquals("source", holder[0].snapshot(100).primary);
        assertEquals("translated secondary", holder[0].snapshot(100).secondary);
    }

    @Test public void transientRetryIsBoundedAndKeepsSuccessfulLanguage() {
        Jobs jobs = new Jobs(); long[] now = {0}; AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (f,t,texts) -> {
            if (t.equals("de")) { first.incrementAndGet(); return texts.stream().map(x -> "de:" + x).toList(); }
            second.incrementAndGet(); throw new IOException("temporary");
        }, jobs, () -> {}, () -> {}, () -> now[0]);
        session.demand(0); jobs.run(); now[0] = 999; session.demand(100); jobs.run();
        assertEquals(1, second.get());
        now[0] = 1000; session.demand(200); jobs.run();
        now[0] = 3000; session.demand(300); jobs.run();
        now[0] = 60000; session.demand(400); jobs.run();
        assertEquals(1, first.get()); assertEquals(3, second.get());
        assertEquals("de:first source", session.snapshot(400).primary);
    }
    @Test public void retryHonorsProviderDelayAndRecoversWithoutSeek() {
        Jobs jobs = new Jobs(); long[] now = {0}; AtomicInteger calls = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "de", null, (f,t,texts) -> {
            if (calls.incrementAndGet() == 1) throw new CaptionTranslator.Failure("limited", 60000, true);
            return texts.stream().map(x -> "de:" + x).toList();
        }, jobs, () -> {}, () -> {}, () -> now[0]);
        session.demand(0); jobs.run(); now[0] = 59999; session.demand(100); jobs.run(); assertEquals(1, calls.get());
        now[0] = 60000; session.demand(200); jobs.run();
        assertEquals(2, calls.get()); assertEquals("de:first source", session.snapshot(200).primary);
    }
    @Test public void fastPlaybackPreparesFurtherAheadAndPreservesSourceClock() {
        Jobs jobs = new Jobs(); List<Integer> sizes = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "de", null, (f,t,texts) -> {
            sizes.add(texts.size()); return texts.stream().map(x -> "de:" + x).toList();
        }, jobs, () -> {}, () -> {});
        session.demand(0, 2); jobs.run(); assertEquals(List.of(3), sizes);
        var cue = session.snapshot(32000); assertEquals(31000, cue.start); assertEquals(34000, cue.end);
    }
    @Test public void lateTranslationGuardUsesWallTimeAtPlaybackSpeed() {
        Jobs jobs = new Jobs(); var session = new CaptionSession(SOURCE, "en", "de", null,
                (f,t,texts) -> texts.stream().map(x -> "de:" + x).toList(), jobs, () -> {}, () -> {});
        session.demand(0, 2); assertEquals("", session.snapshot(0).primary); jobs.run();
        assertEquals("", session.snapshot(1000).primary);
        assertEquals("de:first source", session.snapshot(1000, true).primary);
    }

    @Test public void terminalMissingResultDoesNotEraseCachedPrimarySibling() {
        Jobs jobs = new Jobs(); long[] now = {0}; AtomicInteger calls = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "de", "tr", (f,t,texts) -> {
            if (!t.equals("de")) throw new IOException("secondary unavailable");
            if (calls.incrementAndGet() == 1) return Arrays.asList("eins", null);
            throw new CaptionTranslator.Failure("unsupported", 0, false);
        }, jobs, () -> {}, () -> {}, () -> now[0]);
        session.demand(0); jobs.run(); now[0] = 1000; session.demand(100); jobs.run();
        assertEquals("eins", session.snapshot(200).primary);
    }


    @Test public void uploadedPrimaryTranslatesOnlyMissingSecondary() {
        Jobs jobs = new Jobs(); List<String> targets = new ArrayList<>(); AtomicInteger failures = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "tr", "de", (from, to, texts) -> {
            targets.add(to); return texts.stream().map(text -> to + ":" + text).toList();
        }, jobs, () -> {}, failures::incrementAndGet, false, true);
        session.demand(0); jobs.run();
        assertEquals(List.of("de"), targets);
        var cue = session.snapshot(100);
        assertEquals("", cue.primary); assertEquals("de:first source", cue.secondary);
        assertTrue(cue.complete); assertEquals(0, failures.get());
    }

    @Test public void uploadedSecondaryTranslatesOnlyMissingPrimary() {
        Jobs jobs = new Jobs(); List<String> targets = new ArrayList<>();
        var session = new CaptionSession(SOURCE, "en", "tr", "de", (from, to, texts) -> {
            targets.add(to); return texts.stream().map(text -> to + ":" + text).toList();
        }, jobs, () -> {}, () -> {}, true, false);
        session.demand(0); jobs.run();
        assertEquals(List.of("tr"), targets);
        var cue = session.snapshot(100);
        assertEquals("tr:first source", cue.primary); assertEquals("", cue.secondary);
        assertTrue(cue.complete);
    }

    @Test public void bothUploadedOutputsDoNotQueueOrReadTranslations() {
        Jobs jobs = new Jobs();
        CaptionTranslator translator = new CaptionTranslator() {
            public List<String> translate(String from, String to, List<String> texts) {
                throw new AssertionError("Both outputs are uploaded");
            }
            public String cached(String from, String to, String text) {
                throw new AssertionError("Uploaded slots do not need translation cache reads");
            }
        };
        var session = new CaptionSession(SOURCE, "en", "tr", "de", translator,
                jobs, () -> {}, () -> {}, false, false);
        session.demand(0); assertTrue(jobs.queue.isEmpty());
        var cue = session.snapshot(100);
        assertEquals("", cue.primary); assertEquals("", cue.secondary); assertTrue(cue.complete);
    }

    @Test public void missingOutputFailureDoesNotExposeSourceInUploadedSlot() {
        Jobs jobs = new Jobs(); List<String> targets = new ArrayList<>(); AtomicInteger failures = new AtomicInteger();
        var session = new CaptionSession(SOURCE, "en", "tr", "de", (from, to, texts) -> {
            targets.add(to); throw new IOException("Missing output unavailable");
        }, jobs, () -> {}, failures::incrementAndGet, false, true);
        session.demand(0); jobs.run();
        assertEquals(List.of("de"), targets); assertEquals(1, failures.get());
        var cue = session.snapshot(100);
        assertEquals("", cue.primary); assertEquals("", cue.secondary); assertFalse(cue.complete);
    }

    @Test public void replacementAfterUploadFailureRequestsTheFormerlyOwnedSlot() {
        Jobs jobs = new Jobs(); List<String> targets = new ArrayList<>();
        CaptionTranslator translator = (from, to, texts) -> {
            targets.add(to); return texts.stream().map(text -> to + ":" + text).toList();
        };
        var old = new CaptionSession(SOURCE, "en", "tr", "de", translator,
                jobs, () -> {}, () -> {}, false, true);
        old.demand(0); old.invalidate();
        var replacement = new CaptionSession(SOURCE, "en", "tr", "de", translator,
                jobs, () -> {}, () -> {}, true, true);
        replacement.demand(0); jobs.run();
        assertEquals(List.of("tr", "de"), targets);
        assertEquals("tr:first source", replacement.snapshot(100).primary);
        assertEquals("de:first source", replacement.snapshot(100).secondary);
        assertFalse(old.isActive());
    }
}
