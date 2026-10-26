import android.content.Context;
import android.content.SharedPreferences;
import com.maowu.gravitypet.gengpet.PetStore;
import java.util.*;

/** Executes the unchanged production PetStore with real org.json and an in-memory preferences adapter.
 * Does not claim to test Android disk durability, service ordering, windows, or framework lifecycle. */
public final class PetStoreSessionTest {
  static int assertions;
  static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
  static final class MemoryPreferences implements SharedPreferences {
    final Map<String,Object> values=new HashMap<>();
    public String getString(String k,String fallback){return (String)values.getOrDefault(k,fallback);}
    public float getFloat(String k,float fallback){return ((Number)values.getOrDefault(k,fallback)).floatValue();}
    public boolean getBoolean(String k,boolean fallback){return (Boolean)values.getOrDefault(k,fallback);}
    public Editor edit(){return new Editor(){final Map<String,Object> changes=new HashMap<>();
      public Editor putString(String k,String v){changes.put(k,v);return this;}
      public Editor remove(String k){changes.put(k,null);return this;}
      public void apply(){for(Map.Entry<String,Object> e:changes.entrySet())if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}
    };}
  }
  static final class TestContext extends Context {
    final MemoryPreferences prefs=new MemoryPreferences();
    public Context getApplicationContext(){return this;}
    public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
  }
  static Set<String> ids(TestContext c){Set<String> result=new HashSet<>();for(PetStore.Instance i:PetStore.loadInstances(c))result.add(i.uid);return result;}
  public static void main(String[] args){
    TestContext c=new TestContext();
    PetStore.Instance first=PetStore.add(c,"doro"),second=PetStore.add(c,"sweat_emoji");
    first.softness=.12f;first.sizeDp=104;first.mood="happy";first.moodText="one";PetStore.update(c,first);
    second.softness=.87f;PetStore.update(c,second);
    check(Math.abs(PetStore.find(c,first.uid).softness-.12f)<.001,"first individual softness persists");
    check(Math.abs(PetStore.find(c,second.uid).softness-.87f)<.001,"second individual softness independent");
    PetStore.startNewSession(c);
    check(PetStore.loadInstances(c).isEmpty(),"cold session archives current group");
    check(PetStore.previousCount(c)==2,"cold session retains old group for explicit restore");
    PetStore.Instance fresh=PetStore.add(c,"grudge_panda");
    check(PetStore.loadInstances(c).size()==1,"cold SHOW path leaves exactly one new pet");
    check(!ids(c).contains(first.uid)&&!ids(c).contains(second.uid),"old group does not silently reappear");
    PetStore.Instance warm=PetStore.add(c,"doro");
    check(PetStore.loadInstances(c).size()==2,"warm SHOW appends");
    check(!warm.uid.equals(first.uid),"same character gets independent identity");
    check(Math.abs(warm.softness-PetStore.DEFAULT_SOFTNESS)<.001,"old per-instance softness does not become default");
    PetStore.restorePrevious(c);
    check(PetStore.loadInstances(c).size()==4,"explicit restore merges previous group");
    check(PetStore.previousCount(c)==0,"fully restored archive consumed");
    check(ids(c).containsAll(List.of(first.uid,second.uid,fresh.uid,warm.uid)),"restore preserves all identities");
    check(Math.abs(PetStore.find(c,first.uid).softness-.12f)<.001,"restore preserves first softness");
    check(Math.abs(PetStore.find(c,second.uid).softness-.87f)<.001,"restore preserves second softness");
    check(PetStore.find(c,first.uid).sizeDp==104&&PetStore.find(c,first.uid).moodText.equals("one"),"restore preserves size and mood text");
    PetStore.restorePrevious(c);check(PetStore.loadInstances(c).size()==4,"repeated restore does not duplicate");
    PetStore.clear(c);
    for(int i=0;i<6;i++)check(PetStore.add(c,"doro")!=null,"fill old group");
    Set<String> oldSix=ids(c);PetStore.startNewSession(c);
    PetStore.Instance onlyNew=PetStore.add(c,"grudge_panda");PetStore.restorePrevious(c);
    check(PetStore.loadInstances(c).size()==6,"restore caps visible group at six");
    check(PetStore.previousCount(c)==1,"overflow remains recoverable");
    Set<String> restored=ids(c);check(restored.contains(onlyNew.uid),"restore retains new current member");
    check(PetStore.add(c,"doro")==null,"warm add cannot exceed six");
    check(PetStore.previousCount(c)==1,"failed add leaves archive intact");
    PetStore.restorePrevious(c);check(PetStore.previousCount(c)==1,"restore while full keeps remainder");
    PetStore.remove(c,onlyNew.uid);PetStore.restorePrevious(c);
    check(ids(c).equals(oldSix),"freeing one slot restores exact remaining identity");
    check(PetStore.previousCount(c)==0,"remainder consumed only once restored");
    PetStore.startNewSession(c);PetStore.add(c,"doro");PetStore.clear(c);
    check(PetStore.loadInstances(c).isEmpty()&&PetStore.previousCount(c)==0,"clear erases active and previous groups");
    PetStore.restorePrevious(c);check(PetStore.loadInstances(c).isEmpty(),"clear cannot be undone by restore");
    PetStore.startNewSession(c);PetStore.add(c,"doro");check(PetStore.loadInstances(c).size()==1&&PetStore.previousCount(c)==0,"fresh summon after clear cannot resurrect");
    PetStore.Instance one=PetStore.loadInstances(c).get(0);PetStore.remove(c,one.uid);PetStore.update(c,one);check(PetStore.loadInstances(c).isEmpty(),"stale instance update cannot resurrect removed pet");
    c.prefs.values.put("previous_instances",c.prefs.getString("instances","[]"));
    check(PetStore.add(c,"not_catalogued")==null,"invalid character cannot enter persisted set");
    System.out.println("PASS "+assertions+" PetStore storage assertions (real production source, real JSON, memory preferences). Android lifecycle not executed.");
  }
}
