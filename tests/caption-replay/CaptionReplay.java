package app.morphe.captionreplay;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.*;
import java.util.*;

/** Synthetic replay using the actual patched APK classes and Android renderer. No live-video claim. */
public class CaptionReplay extends Instrumentation {
    final StringBuilder report = new StringBuilder(); int passed;
    android.app.Activity activityContext;
    Object nativeFlagStore;
    Context context; ClassLoader loader; Class<?> cueType, settingsType, layoutType;
    Constructor<?> cueConstructor, settingsConstructor;
    void check(boolean value,String message) { if(!value)throw new AssertionError(message);passed++;report.append("PASS ").append(message).append('\n'); }
    Class<?> cls(String name)throws Exception{return Class.forName(name,true,loader);}
    Object fresh(String name)throws Exception {Constructor<?> c=cls(name).getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    void field(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    Object nativeFeatures()throws Exception {
        // A controlled native flag snapshot enables only the new srv3 span renderer (45773966).
        Object flag=fresh("bbiz");field(flag,"b",1);field(flag,"c",Boolean.TRUE);
        Constructor<?> mapCtor=cls("axos").getDeclaredConstructor(Map.class);mapCtor.setAccessible(true);
        Object flags=fresh("bbiy");field(flags,"b",mapCtor.newInstance(Collections.singletonMap(45773966L,flag)));
        Object snapshot=fresh("azrr");field(snapshot,"v",flags);
        // The service normally owns reactive/network subscriptions; the replay needs only its snapshot.
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Object unsafe=null;
        for(Field f:unsafeType.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType()==unsafeType){f.setAccessible(true);unsafe=f.get(null);break;}
        Object store=unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,cls("ahxv"));field(store,"e",snapshot);nativeFlagStore=store;
        Object reader=cls("ahxz").getConstructor(cls("ahxv"),cls("ahya")).newInstance(store,null);
        Constructor<?> ctor=null;for(Constructor<?> c:cls("aoze").getConstructors())if(c.getParameterTypes()[0]==cls("ahxv")){ctor=c;break;}
        Object features=ctor.newInstance(new Object[19]);field(features,"s",reader);field(features,"i",reader);
        check((boolean)features.getClass().getMethod("aZ").invoke(features),"controlled native srv3 span feature enabled");
        return features;
    }
    Object cue(int id,int y,CharSequence text,CharSequence full)throws Exception {
        Object settings=settingsConstructor.newInstance(34,50,y,true,false);
        return cueConstructor.newInstance(id,1000L,text,full,settings);
    }
    List<?> reflow(List<?> frame,boolean translated,boolean enabled)throws Exception {
        return (List<?>)layoutType.getMethod("reflow",List.class,boolean.class,boolean.class).invoke(null,frame,translated,enabled);
    }
    void picture(List<?> frame,String file)throws Exception {
        ViewGroup overlay=(ViewGroup)cls("aose").getConstructor(Context.class).newInstance(context);
        overlay.getClass().getMethod("kq",List.class).invoke(overlay,frame);
        overlay.measure(View.MeasureSpec.makeMeasureSpec(2184,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1968,View.MeasureSpec.EXACTLY));
        overlay.layout(0,0,2184,1968);
        Bitmap bitmap=Bitmap.createBitmap(2184,1968,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff20242a);overlay.draw(canvas);
        try(var out=new FileOutputStream(new File(context.getExternalFilesDir(null),file))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        check(overlay.getChildCount()==frame.size(),file+" native child count="+overlay.getChildCount());
        for(int i=0;i<overlay.getChildCount();i++){
            View v=overlay.getChildAt(i);
            check(v.getMeasuredWidth()>0 && v.getMeasuredHeight()>0,file+" window measured");
            report.append("WINDOW ").append(file).append(' ').append(v.getLeft()).append(',').append(v.getTop()).append(' ').append(v.getWidth()).append('x').append(v.getHeight()).append('\n');
            check(v.getLeft()>=-1 && v.getRight()<=2185 && v.getTop()>=-1 && v.getBottom()<=1969,file+" window inside viewport (native rounding <=1px)");
        }
    }
    void test()throws Exception {
        context=activityContext==null?getTargetContext():activityContext;loader=context.getClassLoader();
        cueType=cls("apoo");settingsType=cls("apol");layoutType=cls("app.morphe.extension.youtube.patches.TranslatedCaptionLayoutPatch");
        settingsConstructor=settingsType.getConstructor(int.class,int.class,int.class,boolean.class,boolean.class);
        cueConstructor=cueType.getConstructor(int.class,long.class,CharSequence.class,CharSequence.class,settingsType);
        check((boolean)layoutType.getMethod("isIncluded").invoke(null),"optional patch included");
        SpannableString first=new SpannableString("uygunsuz bir zamanda gelen bir güncelleme. Belki\nde Edge,");
        RelativeSizeSpan relative=new RelativeSizeSpan(0.75f);
        first.setSpan(relative,0,first.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        first.setSpan(new ForegroundColorSpan(0x00000000),0,first.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Object upper=cue(8,80,first,"future text used only for sizing");
        Object lower=cue(1,95,"varsayılan web tarayıcınız olmak için karanlık bir\ndesenleme","a different full text");
        List<?> input=Arrays.asList(lower,upper);
        check(reflow(input,false,true)==input,"original track keeps native objects");
        check(reflow(input,true,false)==input,"off switch keeps native objects");
        List<?> output=reflow(input,true,true);
        check(output.size()==1,"two simultaneous windows become one");
        Object merged=output.get(0);
        CharSequence text=(CharSequence)cueType.getField("d").get(merged);
        check(text.toString().equals("uygunsuz bir zamanda gelen bir güncelleme. Belki de Edge, varsayılan web tarayıcınız olmak için karanlık bir desenleme"),"visual reading order and hard-break normalization");
        check(text==cueType.getField("e").get(merged),"visible text and measurement text are identical");
        check(cueType.getField("b").getLong(merged)==1000L,"native cue time retained");
        check(cueType.getField("a").getInt(merged)==Integer.MIN_VALUE,"stable merged window id");
        check(text instanceof Spanned,"Android spans retained");
        Spanned styled=(Spanned)text;
        check(styled.getSpans(0,styled.length(),RelativeSizeSpan.class).length==1,"relative source font size retained");
        check(styled.getSpans(0,styled.length(),ForegroundColorSpan.class).length==0,"invisible source foreground removed");
        check(first.getSpans(0,first.length(),ForegroundColorSpan.class).length==1,"source spans are not mutated");
        check(cueType.getField("d").get(upper)==first,"source cue remains unchanged");
        Object mergedSettings=cueType.getField("c").get(merged);
        check(settingsType.getField("c").getInt(mergedSettings)==50 && settingsType.getField("d").getInt(mergedSettings)==95,"center-bottom native anchor");
        SpannableString visibleFirst=new SpannableString(first);
        for(ForegroundColorSpan span:visibleFirst.getSpans(0,visibleFirst.length(),ForegroundColorSpan.class))visibleFirst.removeSpan(span);
        List<?> visualInput=Arrays.asList(lower,cue(8,80,visibleFirst,visibleFirst));
        picture(visualInput,"caption-before.png");picture(reflow(visualInput,true,true),"caption-after.png");
        // Read metadata from two distinct parsed timelines to catch thread/global state leaks.
        Class<?> pens=cls("app.morphe.extension.youtube.patches.TranslatedCaptionPensPatch");
        Class<?> requestInterface=cls("app.morphe.extension.youtube.patches.TranslatedCaptionPensPatch$CaptionRequestInterface");
        Class<?> trackInterface=cls("app.morphe.extension.youtube.patches.PreferredCaptionLanguagePatch$CaptionTrackInterface");
        Object track=Proxy.newProxyInstance(loader,new Class[]{trackInterface},(p,m,a)->m.getName().equals("patch_getVssId")?"ta.en.tr":"tr");
        Object request=Proxy.newProxyInstance(loader,new Class[]{requestInterface},(p,m,a)->track);
        Method set=pens.getMethod("setParsedRequest",Object.class);set.invoke(null,request);
        Object translatedTimeline=cls("apnt").getConstructor(List.class).newInstance(Collections.emptyList());
        Field provenance=translatedTimeline.getClass().getDeclaredField("morpheTranslated");provenance.setAccessible(true);
        check(provenance.getBoolean(translatedTimeline),"translated provenance captured on timeline");
        set.invoke(null,new Object[]{null});
        Object originalTimeline=cls("apnt").getConstructor(List.class).newInstance(Collections.emptyList());
        check(!provenance.getBoolean(originalTimeline) && provenance.getBoolean(translatedTimeline),"cached timelines retain separate provenance");
        // Exercise actual source start/end intervals, not fabricated apoo sample timestamps.
        set.invoke(null,request);
        Class<?> textTimeline=cls("apor"), positionTimeline=cls("apon"), window=cls("apok");
        Object firstTexts=textTimeline.getConstructor(List.class,List.class,List.class).newInstance(Arrays.asList(1000L),Arrays.asList(3000L),Arrays.asList("first"));
        Object secondTexts=textTimeline.getConstructor(List.class,List.class,List.class).newInstance(Arrays.asList(1500L),Arrays.asList(4000L),Arrays.asList("second"));
        Object pos=positionTimeline.getConstructor(List.class,List.class).newInstance(Arrays.asList(0L),Arrays.asList(settingsConstructor.newInstance(34,50,95,true,false)));
        Constructor<?> wc=window.getConstructor(int.class,textTimeline,positionTimeline);
        Object timeline=cls("apnt").getConstructor(List.class).newInstance(Arrays.asList(wc.newInstance(1,firstTexts,pos),wc.newInstance(2,secondTexts,pos)));
        Method sample=timeline.getClass().getMethod("c",long.class);
        List<?> overlap=(List<?>)sample.invoke(timeline,2000L);
        report.append("NATIVE overlap=").append(overlap).append(" active=").append(layoutType.getMethod("isActive").invoke(null)).append(" translated=").append(provenance.getBoolean(timeline)).append('\n');
        check(overlap.size()==1 && cueType.getField("d").get(overlap.get(0)).toString().equals("first second"),"native overlapping source intervals reflow at current sample");
        List<?> expired=(List<?>)sample.invoke(timeline,3500L);
        check(expired.size()==1 && cueType.getField("d").get(expired.get(0)).toString().equals("second"),"expired source window disappears without stale merged text");
        List<?> ended=(List<?>)sample.invoke(timeline,4500L);
        boolean clear=true;for(Object c:ended){CharSequence t=(CharSequence)cueType.getField("d").get(c);if(t!=null&&t.length()!=0)clear=false;}
        check(clear,"native source end intervals still clear captions");
        set.invoke(null,new Object[]{null});
        // Actual YouTube XML dispatch + srv3 handlers + native pen spans + native timeline.
        set.invoke(null,request);
        Class<?> featureType=cls("aoze");
        Object features=nativeFeatures();
        Object registry=cls("bsdg").getConstructor(short[].class).newInstance(new Object[]{null});
        cls("ammg").getMethod("az",cls("bsdg"),boolean.class,featureType).invoke(null,registry,false,features);
        cls("ammg").getMethod("aA",cls("bsdg"),boolean.class,featureType).invoke(null,registry,false,features);
        Object config=registry.getClass().getMethod("af").invoke(registry);
        Object xml=cls("adwc").getConstructor(Map.class).newInstance(Collections.emptyMap());
        byte[] fixture;
        try(java.io.InputStream in=getContext().getAssets().open("translated-two-windows.srv3")){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);fixture=out.toByteArray();}
        Object bytes=cls("adjg").getConstructor(java.nio.ByteBuffer.class).newInstance(java.nio.ByteBuffer.wrap(fixture));
        Method parse=null;
        for(Method m:xml.getClass().getMethods())if(m.getName().equals("a")&&m.getParameterCount()==2&&m.getParameterTypes()[0].isInstance(bytes)&&m.getParameterTypes()[1].isInstance(config)){parse=m;break;}
        if(parse==null)throw new NoSuchMethodException("native XML dispatcher");
        Object builder=parse.invoke(xml,bytes,config);
        Object parsed=builder.getClass().getMethod("a").invoke(builder);
        List<?> parsedFrame=(List<?>)parsed.getClass().getMethod("c",long.class).invoke(parsed,2000L);
        report.append("SRV3 frame=").append(parsedFrame).append('\n');
        check(parsedFrame.size()==1,"native srv3 parser produces one reflowed translated window");
        CharSequence parsedText=(CharSequence)cueType.getField("d").get(parsedFrame.get(0));
        check(parsedText.toString().equals("first line second line"),"native srv3 segments and windows preserve visual order");
        Spanned parsedSpans=(Spanned)parsedText;
        android.text.style.StyleSpan[] styles=parsedSpans.getSpans(0,parsedSpans.length(),android.text.style.StyleSpan.class);
        check(styles.length>0 && styles[0].getStyle()==3,"native parser retains bold plus italic");
        check(parsedSpans.getSpans(0,parsedSpans.length(),android.text.style.UnderlineSpan.class).length>0,"native parser retains underline");
        RelativeSizeSpan[] sizes=parsedSpans.getSpans(0,parsedSpans.length(),RelativeSizeSpan.class);
        check(sizes.length>0 && Math.abs(sizes[0].getSizeChange()-0.5f)<0.001f,"native srv3 sz50 survives default-pen safety");
        boolean opaque=true;for(ForegroundColorSpan span:parsedSpans.getSpans(0,parsedSpans.length(),ForegroundColorSpan.class))if((span.getForegroundColor()>>>24)==0)opaque=false;
        check(opaque,"native srv3 invisible pen cannot hide translated text");
        List<?> parsedExpired=(List<?>)parsed.getClass().getMethod("c",long.class).invoke(parsed,3500L);
        check(parsedExpired.size()==1&&cueType.getField("d").get(parsedExpired.get(0)).toString().equals("second line"),"native srv3 source interval expires after reflow");
        set.invoke(null,new Object[]{null});
        // Native pen size-only copy cannot reintroduce alpha zero.
        Object pen=cls("apny").getConstructor().newInstance();cls("apny").getField("g").setInt(pen,50);cls("apny").getField("m").setInt(pen,0);
        cls("apny").getField("c").setBoolean(pen,true);cls("apny").getField("d").setBoolean(pen,true);cls("apny").getField("e").setBoolean(pen,true);
        Object safe=pen.getClass().getMethod("patch_copySizeOnly").invoke(pen);
        check(safe!=pen && safe.getClass().getField("g").getInt(safe)==50,"native pen copy preserves font size");
        check(safe.getClass().getField("c").getBoolean(safe) && safe.getClass().getField("d").getBoolean(safe) && safe.getClass().getField("e").getBoolean(safe),"native safe emphasis retained");
        check(safe.getClass().getField("m").getInt(safe)==255 && pen.getClass().getField("m").getInt(pen)==0,"safe pen resets invisible alpha without mutation");
        // Real Android clock, real View and native font formula at the logged Fold-like dimensions.
        Class<?> fix=cls("app.morphe.extension.youtube.patches.CaptionAspectRatioFix");
        Method normalize=fix.getMethod("normalizeHeight",View.class,float.class,int.class,int.class);
        View view=new View(context);
        normalize.invoke(null,view,1f,2184,1227);SystemClock.sleep(600);normalize.invoke(null,view,1f,2184,1227);
        int effective=(int)normalize.invoke(null,view,1f,2184,1968);
        check(effective==1227,"learned viewport maps 2184x1968 to 2184x1227");
        Object overlay=cls("aose").getConstructor(Context.class).newInstance(context);
        Method font=cls("aose").getDeclaredMethod("f",float.class,int.class,int.class);font.setAccessible(true);
        float original=(float)font.invoke(overlay,1f,2184,1968), corrected=(float)font.invoke(overlay,1f,2184,effective);
        report.append("FONT original=").append(original).append(" corrected=").append(corrected).append(" density=").append(context.getResources().getDisplayMetrics().scaledDensity).append('\n');
        check(Math.abs(corrected/original-1227f/1968f)<0.0001f,"native font calculation follows effective viewport");
        check((int)normalize.invoke(null,view,1f,1080,608)==608,"normal viewport unchanged");
        // Execute the actual nll selector and injected geometry hook with controlled dynamic mode.
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Object unsafe=null;
        for(Field f:unsafeType.getDeclaredFields())if(Modifier.isStatic(f.getModifiers())&&f.getType()==unsafeType){f.setAccessible(true);unsafe=f.get(null);break;}
        Object modeFlags=unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,cls("bpak"));
        Field storeField=cls("ahxz").getDeclaredField("b");storeField.setAccessible(true);storeField.set(modeFlags,nativeFlagStore);
        check(!(boolean)modeFlags.getClass().getMethod("fB").invoke(modeFlags),"controlled nll dynamic mode selected");
        Object nativeOverlay=cls("nll").getConstructor(Context.class).newInstance(context);cls("nll").getField("o").set(nativeOverlay,modeFlags);
        for(Method m:fix.getMethods())if(m.getName().equals("newVideoStarted"))m.invoke(null,new Object[]{null});
        Method selector=cls("nll").getDeclaredMethod("f",float.class,int.class,int.class);selector.setAccessible(true);
        float cold=(float)selector.invoke(nativeOverlay,1f,2184,1968);
        check(Math.abs(cold-46.857143f)<0.001f,"nll cold fullscreen without reference remains native");
        selector.invoke(nativeOverlay,1f,2184,1227);SystemClock.sleep(600);selector.invoke(nativeOverlay,1f,2184,1227);
        float nativeCorrected=(float)selector.invoke(nativeOverlay,1f,2184,1968);
        check(Math.abs(nativeCorrected-29.214285f)<0.001f,"actual nll hook returns learned 29.214285sp");

    }
    @Override public void onCreate(Bundle arguments){ super.onCreate(arguments); start(); }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            android.content.Intent launch=new android.content.Intent();
            launch.setClassName("app.morphe.captionlayoutlab","com.google.android.apps.youtube.app.watchwhile.InternalMainActivity");
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activityContext=startActivitySync(launch);
            final Throwable[] problem={null};runOnMainSync(()->{try{test();}catch(Throwable e){problem[0]=e;}});if(problem[0]!=null)throw problem[0];result.putString("stream",report+"PASS TOTAL "+passed+"\n");finish(-1,result);}
        catch(Throwable e){java.io.StringWriter sw=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(sw));result.putString("stream",report+"FAIL "+sw);finish(0,result);}
    }
}
