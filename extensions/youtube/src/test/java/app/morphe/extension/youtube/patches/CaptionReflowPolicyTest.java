package app.morphe.extension.youtube.patches;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
public class CaptionReflowPolicyTest {
 static final class Cue implements CaptionReflowPolicy.Cue {
  int id,x=50,y;long time=1000;boolean visible=true,scroll;CharSequence text;
  Cue(int id,int y,String text){this.id=id;this.y=y;this.text=text;}
  public int patch_getId(){return id;} public long patch_getTime(){return time;}
  public int patch_getX(){return x;} public int patch_getY(){return y;}
  public boolean patch_isVisible(){return visible;} public boolean patch_isScrollable(){return scroll;}
  public CharSequence patch_getText(){return text;}
  public Object patch_withText(CharSequence text){return new Cue(id,95,text.toString());}
 }
 @Test public void originalAndDisabledTracksBypass(){var c=List.of(new Cue(1,90,"Hello"));assertNull(CaptionReflowPolicy.select(c,false,true));assertNull(CaptionReflowPolicy.select(c,true,false));}
 @Test public void visualOrderDoesNotDependOnHashMap(){var low=new Cue(1,95,"second");var high=new Cue(8,80,"first");assertEquals(List.of(high,low),CaptionReflowPolicy.select(List.of(low,high),true,true));}
 @Test public void sameRowUsesHorizontalOrder(){var right=new Cue(1,95,"right");right.x=80;var left=new Cue(2,95,"left");left.x=20;assertEquals(List.of(left,right),CaptionReflowPolicy.select(List.of(right,left),true,true));}
 @Test public void hiddenAndEmptyWindowsCannotCreateGhostText(){var hidden=new Cue(1,90,"hidden");hidden.visible=false;var visible=new Cue(2,95,"visible");assertNull(CaptionReflowPolicy.select(List.of(hidden,new Cue(3,80,""),visible),true,true));var second=new Cue(4,99,"second");assertEquals(List.of(visible,second),CaptionReflowPolicy.select(List.of(second,hidden,visible),true,true));}
 @Test public void differentSampleTimesCannotMerge(){var a=new Cue(1,80,"first");var b=new Cue(2,95,"second");b.time=2000;assertNull(CaptionReflowPolicy.select(List.of(a,b),true,true));}
 @Test public void nonScrollingSingleWindowPreservesNativeLayout(){var single=new Cue(5,95,"first\\nsecond");assertNull(CaptionReflowPolicy.select(List.of(single),true,true));}
 @Test public void scrollingCaptionsRemainNative(){var c=new Cue(1,95,"rolling");c.scroll=true;assertNull(CaptionReflowPolicy.select(List.of(c),true,true));}
 @Test public void overlappingScrollableTranslatedWindowsCanBeReflowed(){var old=new Cue(1,80,"uygunsuz bir zamanda gelen bir güncelleme. Belki de Edge,");old.scroll=true;var current=new Cue(2,95,"varsayılan web tarayıcınız olmak için karanlık bir desenleme uygulanıyordur.");current.scroll=true;assertEquals(List.of(old,current),CaptionReflowPolicy.select(List.of(old,current),true,true));}
 @Test public void unknownObjectsFailClosed(){assertNull(CaptionReflowPolicy.select(List.of(new Object()),true,true));assertNull(CaptionReflowPolicy.select(null,true,true));}
 @Test public void emptyFrameClearsNormally(){assertNull(CaptionReflowPolicy.select(List.of(),true,true));}
 @Test public void immutableInputIsNotMutated(){var a=new Cue(1,95,"lower");var b=new Cue(2,80,"upper");var input=List.of(a,b);CaptionReflowPolicy.select(input,true,true);assertEquals(List.of(a,b),input);}
 @Test public void hugePayloadBypasses(){assertNull(CaptionReflowPolicy.select(List.of(new Cue(1,95,"x".repeat(4097))),true,true));}
}
