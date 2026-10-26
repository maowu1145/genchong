from pathlib import Path
import subprocess

REPO=Path(__file__).resolve().parents[2]
ROOT=REPO/'work'/'preload-test'
ROOT.mkdir(parents=True,exist_ok=True)
sources={
'android/content/Context.java':'''package android.content; public class Context { public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();} public Context getApplicationContext(){return this;} }''',
'android/content/res/AssetManager.java':'''package android.content.res; import java.io.*; public class AssetManager {public InputStream open(String p)throws IOException {if(p.contains("fail"))throw new IOException("synthetic missing file"); return new ByteArrayInputStream(new byte[]{1});}}''',
'android/graphics/Bitmap.java':'''package android.graphics; public class Bitmap {public enum Config{ARGB_8888} public int getAllocationByteCount(){return 1024;} public boolean isRecycled(){return false;}}''',
'android/graphics/BitmapFactory.java':'''package android.graphics; import java.io.*; import java.util.concurrent.*; import java.util.concurrent.atomic.*; public class BitmapFactory {public static final AtomicInteger decodes=new AtomicInteger(); public static volatile CountDownLatch entered,release; public static class Options{public boolean inJustDecodeBounds,inScaled,inMutable;public int outWidth,outHeight;public Bitmap.Config inPreferredConfig;}public static Bitmap decodeStream(InputStream in,Object rect,Options o){o.outWidth=512;o.outHeight=512;if(o.inJustDecodeBounds)return null;decodes.incrementAndGet();CountDownLatch e=entered,r=release;if(e!=null){entered=null;e.countDown();try{if(!r.await(5,TimeUnit.SECONDS))throw new RuntimeException("gate timeout");}catch(InterruptedException x){throw new RuntimeException(x);}}return new Bitmap();}}''',
'android/os/Looper.java':'''package android.os; public class Looper {private static final Looper MAIN=new Looper();public static Looper getMainLooper(){return MAIN;}}''',
'android/os/Handler.java':'''package android.os; import java.util.concurrent.*; public class Handler {static final ConcurrentLinkedQueue<Runnable> Q=new ConcurrentLinkedQueue<>();public Handler(Looper l){} public boolean post(Runnable r){Q.add(r);return true;}public static void drain(){Runnable r;while((r=Q.poll())!=null)r.run();}}''',
'android/util/Log.java':'''package android.util;public class Log {public static int e(String t,String m,Throwable e){System.out.println("EXPECTED_LOG "+m);return 0;}}''',
'android/util/LruCache.java':'''package android.util; import java.util.*;public class LruCache<K,V>{final Map<K,V> m=new LinkedHashMap<>();public LruCache(int n){} protected int sizeOf(K k,V v){return 1;}public synchronized V get(K k){return m.get(k);}public synchronized V put(K k,V v){return m.put(k,v);}public synchronized void evictAll(){m.clear();}public synchronized int size(){int s=0;for(Map.Entry<K,V> e:m.entrySet())s+=sizeOf(e.getKey(),e.getValue());return s;}}''',
'org/json/JSONException.java':'''package org.json;public class JSONException extends Exception{public JSONException(String s){super(s);}}''',
'org/json/JSONArray.java':'''package org.json;import java.util.*;public class JSONArray{public final List<Object> a=new ArrayList<>();public int length(){return a.size();}public String getString(int i)throws JSONException{return(String)a.get(i);}public JSONObject getJSONObject(int i)throws JSONException{return(JSONObject)a.get(i);}}''',
'org/json/JSONObject.java':'''package org.json;import java.util.*;public class JSONObject{public final Map<String,Object> m=new LinkedHashMap<>();public JSONObject(){}public JSONObject(String s)throws JSONException{throw new JSONException("not used in injected catalog");}public JSONObject put(String k,Object v){m.put(k,v);return this;}public String getString(String k)throws JSONException{return(String)m.get(k);}public int getInt(String k)throws JSONException{return((Number)m.get(k)).intValue();}public double getDouble(String k)throws JSONException{return((Number)m.get(k)).doubleValue();}public JSONObject getJSONObject(String k)throws JSONException{return(JSONObject)m.get(k);}public JSONArray getJSONArray(String k)throws JSONException{return(JSONArray)m.get(k);}public JSONObject optJSONObject(String k){return(JSONObject)m.get(k);}public JSONArray optJSONArray(String k){return(JSONArray)m.get(k);}public boolean optBoolean(String k,boolean d){return m.containsKey(k)?(Boolean)m.get(k):d;}public int optInt(String k,int d){return m.containsKey(k)?((Number)m.get(k)).intValue():d;}public double optDouble(String k,double d){return m.containsKey(k)?((Number)m.get(k)).doubleValue():d;}public Iterator<String> keys(){return m.keySet().iterator();}}''',
'PreloadRegression.java':r'''import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import com.maowu.gravitypet.gengpet.PetCatalog;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class PreloadRegression {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void await(AtomicInteger a,int n)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(a.get()<n&&System.nanoTime()<until){Handler.drain();Thread.sleep(2);}Handler.drain();check(a.get()==n,"callback count "+a.get()+" expected "+n);}
 static Object field(String n)throws Exception{Field f=PetCatalog.class.getDeclaredField(n);f.setAccessible(true);return f.get(null);}
 public static void main(String[] args)throws Exception{
  List<PetCatalog.Entry> entries=new ArrayList<>();Map<String,PetCatalog.Entry> map=new LinkedHashMap<>();Set<String> paths=new HashSet<>();
  Constructor<PetCatalog.Entry> ec=PetCatalog.Entry.class.getDeclaredConstructor(JSONObject.class);ec.setAccessible(true);
  for(int i=0;i<13;i++){String id=i==12?"pet_fail":"pet_"+i;String path="pets/"+id+"/idle.png";
   JSONObject j=new JSONObject().put("id",id).put("name",id).put("description","test").put("feature","test").put("idle",path)
    .put("states",new JSONObject().put("idle",path)).put("anchor",new JSONObject().put("x",.5).put("y",.8))
    .put("canvas",new JSONObject().put("width",512).put("height",512));
   PetCatalog.Entry e=ec.newInstance(j);entries.add(e);map.put(id,e);paths.add(path);
  }
  Class<?> cc=Class.forName("com.maowu.gravitypet.gengpet.PetCatalog$Catalog");Constructor<?> c=cc.getDeclaredConstructors()[0];c.setAccessible(true);
  Field cf=PetCatalog.class.getDeclaredField("catalog");cf.setAccessible(true);cf.set(null,c.newInstance(entries,map,paths));
  Context ctx=new Context();AtomicInteger done=new AtomicInteger();Thread main=Thread.currentThread();
  Runnable callback=()->{check(Thread.currentThread()==main,"callback off main loop");done.incrementAndGet();};
  BitmapFactory.entered=new CountDownLatch(1);BitmapFactory.release=new CountDownLatch(1);CountDownLatch entered=BitmapFactory.entered;
  check(PetCatalog.preload(ctx,entries.get(0),callback),"first request rejected");check(entered.await(2,TimeUnit.SECONDS),"load not started");
  check(PetCatalog.preload(ctx,entries.get(0),callback),"duplicate subscriber rejected");
  check(PetCatalog.preload(ctx,entries.get(0),()->{throw new IllegalStateException("disposed subscriber");}),"throwing subscriber rejected");
  check(PetCatalog.preload(ctx,entries.get(0),callback),"post-throw subscriber rejected");
  long start=System.nanoTime();for(int i=1;i<12;i++)check(PetCatalog.preload(ctx,entries.get(i),callback),"queue request rejected");
  check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(250),"UI subscription blocked on image decode");
  check(done.get()==0,"callback ran before decode completion");BitmapFactory.release.countDown();await(done,14);
  check(BitmapFactory.decodes.get()==12,"duplicate decode");
  System.out.println("PASS duplicate views, 12-character burst, no UI decode-lock wait, subscriber isolation, main dispatch");
  check(PetCatalog.preload(ctx,entries.get(0),callback),"cached request rejected");check(done.get()==14,"cached callback invoked inline");await(done,15);check(BitmapFactory.decodes.get()==12,"cached re-decode");
  PetCatalog.preload(ctx,entries.get(0),()->{done.incrementAndGet();PetCatalog.preload(ctx,entries.get(0),callback);});await(done,17);
  System.out.println("PASS cached new view notification and callback reentrancy");
  PetCatalog.clearBitmapCache();PetCatalog.preload(ctx,entries.get(0),callback);await(done,18);check(BitmapFactory.decodes.get()==13,"cache clear reload missing");
  PetCatalog.preload(ctx,entries.get(12),callback);await(done,19);PetCatalog.preload(ctx,entries.get(12),callback);await(done,20);
  System.out.println("PASS cache clear reload and missing-image completion");
  ThreadPoolExecutor loader=(ThreadPoolExecutor)field("LOADER");CountDownLatch busy=new CountDownLatch(1),release=new CountDownLatch(1);
  loader.execute(()->{busy.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}});check(busy.await(2,TimeUnit.SECONDS),"worker not occupied");
  for(int i=0;i<6;i++)loader.execute(()->{});
  PetCatalog.clearBitmapCache();check(PetCatalog.preload(ctx,entries.get(1),callback),"saturated request rejected");await(done,21);release.countDown();
  check(((Map<?,?>)field("WARMING")).isEmpty(),"subscriptions retained after completion");
  System.out.println("PASS executor-full recovery preserves callback, no stale subscriptions");
  check(!PetCatalog.preload(ctx,null,callback),"invalid entry accepted");Handler.drain();check(done.get()==21,"invalid entry called callback");
  System.out.println("PASS all preload regression scenarios; 21 successful subscriber notifications");
 }
}'''
}
for name,content in sources.items():
 p=ROOT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,'utf-8')
jdk=Path(r'C:\Users\STG-ARTIST2\AppData\Local\jdk17\jdk-17.0.20.1+1\bin')
production=REPO/'app/src/main/java/com/maowu/gravitypet/gengpet/PetCatalog.java'
classes=ROOT/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(jdk/'javac.exe'),'--release','17','-d',str(classes),str(production),*[str(ROOT/name) for name in sources]],check=True)
run=subprocess.run([str(jdk/'java.exe'),'-cp',str(classes),'PreloadRegression'],capture_output=True,text=True)
(ROOT/'result.txt').write_text(run.stdout+run.stderr,'utf-8')
print(run.stdout+run.stderr)
raise SystemExit(run.returncode)
