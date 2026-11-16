package com.gathin.tvhome;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ShelfItemsTest {
 static final long NOW=1800000000000L;
 ShelfItems.Item item(String pkg,String title,String art,boolean resume,long position,long duration,long when,int rank){
  return new ShelfItems.Item(pkg,title,art,"intent:"+title,resume,position,duration,when,rank);
 }
 ShelfItems.Item entry(String title,boolean resume,long when,int rank){return item("watch.kino",title,"https://art/"+title,resume,20,100,when,rank);}
 @Test public void continuationPrecedesCatalogueAndNewestComesFirst(){
  List<ShelfItems.Item> r=ShelfItems.choose("watch.kino",Arrays.asList(entry("catalogue",false,0,0),entry("older",true,NOW-2000,0),entry("newer",true,NOW-1000,0)),20,NOW);
  assertEquals(Arrays.asList("newer","older","catalogue"),titles(r));
 }
 @Test public void filtersOtherAppsMissingArtworkAndUnsafeImageSchemes(){
  List<ShelfItems.Item> r=ShelfItems.choose("watch.kino",Arrays.asList(entry("valid",false,0,0),item("other","other","https://art/a",false,0,0,0,0),item("watch.kino","missing","",false,0,0,0,0),item("watch.kino","private","file:///data/user/0/a",false,0,0,0,0)),20,NOW);
  assertEquals(Collections.singletonList("valid"),titles(r));
 }
 @Test public void duplicatePreviewDoesNotReplaceContinuation(){
  ShelfItems.Item preview=entry("same",false,0,0);ShelfItems.Item resume=entry("same",true,NOW,0);
  List<ShelfItems.Item> r=ShelfItems.choose("watch.kino",Arrays.asList(preview,resume),20,NOW);
  assertEquals(1,r.size());assertSame(resume,r.get(0));
 }
 @Test public void oldFinishedAndFutureContinuationsAreExcluded(){
  List<ShelfItems.Item> r=ShelfItems.choose("watch.kino",Arrays.asList(entry("stale",true,NOW-100L*86400000L,0),item("watch.kino","finished","https://art/end",true,99,100,NOW,0),entry("future",true,NOW+2L*86400000L,0),entry("current",true,NOW,0)),20,NOW);
  assertEquals(Collections.singletonList("current"),titles(r));
 }
 @Test public void channelRankAndLimitAreStable(){
  List<ShelfItems.Item> r=ShelfItems.choose("watch.kino",Arrays.asList(entry("new",false,0,2),entry("watching1",false,0,0),entry("watching2",false,0,0)),2,NOW);
  assertEquals(Arrays.asList("watching1","watching2"),titles(r));
 }
 @Test public void progressIsOnlyShownForRealContinuationAndValidDuration(){
  assertEquals(.2f,entry("x",true,NOW,0).progress(),.0001f);
  assertEquals(0f,entry("x",false,0,0).progress(),0f);
  assertEquals(0f,item("watch.kino","x","https://art/x",true,20,0,NOW,0).progress(),0f);
 }
 @Test public void fiveCompletePostersFitAndTheSixthExtendsPastScreen(){
  int width=ShelfItems.posterWidth(1920,84,48);
  assertEquals(312,width);assertEquals(1920,2*84+5*width+4*48);
  assertTrue(84+5*(width+48)<1920);assertTrue(84+5*(width+48)+width>1920);
 }
 @Test public void twoVideoBannersFillTheSameContentWidthAsFivePosters(){
  int poster=ShelfItems.posterWidth(1920,84,48),height=468;
  int banner=ShelfItems.bannerWidth(height);
  assertEquals(852,banner);
  assertEquals(426,ShelfItems.bannerWidth(234));
  assertEquals(240,ShelfItems.bannerHeight(234));
  assertEquals(480,ShelfItems.bannerHeight(height));
  assertEquals(1920,2*84+2*banner+48);
  assertEquals(-20,ShelfItems.bannerTopOffset(height));
  assertEquals(-10,ShelfItems.bannerTopOffset(234));
  assertEquals(-28,2*ShelfItems.bannerTopOffset(height)+ShelfItems.bannerHeight(height)-height);
  assertTrue(84+2*banner+48<1920);
  assertTrue(84+2*(banner+48)<1920);
  assertTrue(84+2*(banner+48)+banner>1920);
  assertTrue(ShelfItems.landscape("com.vk.tv"));
  assertFalse(ShelfItems.landscape("watch.kino"));
 }
 @Test public void captionPrefersRussianRegardlessOfTranslationOrder(){
  assertEquals("Джентльмены",ShelfItems.displayTitle("Джентльмены / The Gentlemen"));
  assertEquals("Джентльмены",ShelfItems.displayTitle("The Gentlemen / Джентльмены"));
  assertEquals("Ёлки",ShelfItems.displayTitle("Yolki / Ёлки"));
 }
 @Test public void captionKeepsEnglishFallbackAndLiteralSlashes(){
  assertEquals("The Gentlemen",ShelfItems.displayTitle("The Gentlemen"));
  assertEquals("12 12 12",ShelfItems.displayTitle("12 12 12 / 12 12 12"));
  assertEquals("Face/Off",ShelfItems.displayTitle("Face/Off"));
  assertEquals("",ShelfItems.displayTitle(null));
 }
 @Test public void artworkShapeUsesActualPixelsRatherThanSourceOrUrl(){
  assertEquals(852,ShelfItems.artworkWidth(460,259,468));
  assertEquals(852,ShelfItems.artworkWidth(1920,1080,468));
  assertEquals(312,ShelfItems.artworkWidth(500,750,468));
  assertEquals(312,ShelfItems.artworkWidth(460,690,468));
 }
 static List<String> titles(List<ShelfItems.Item> r){List<String> n=new ArrayList<>();for(ShelfItems.Item i:r)n.add(i.title);return n;}
}
