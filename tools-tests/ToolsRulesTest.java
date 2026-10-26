import com.maowu.gravitypet.gengpet.KinshipEngine;
import com.maowu.gravitypet.gengpet.ReminderRules;
import com.maowu.gravitypet.gengpet.ThursdayCalendar;
import java.time.*;
import java.util.*;

/** Standalone Java 17 acceptance tests. No Android runtime, JUnit or downloaded dependencies. */
public final class ToolsRulesTest {
    static int checks=0,kinshipChecks=0;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void relation(String path,String expected) {relation(KinshipEngine.Sex.UNKNOWN,path,expected);}
    static void relation(KinshipEngine.Sex sex,String path,String expected){kinshipChecks++;KinshipEngine.Result r=KinshipEngine.calculate(sex,path.isEmpty()?List.of():Arrays.asList(path.split(",")));check(r.supported&&r.text.contains(expected),"Relation "+path+" expected "+expected+" got "+r.text);}
    static long at(String local){return LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();}
    static Clock clock(String utc,String zone){return Clock.fixed(Instant.parse(utc),ZoneId.of(zone));}
    public static void main(String[] args) {
        relation("","自己");relation("f","爸爸");relation("m","妈妈");relation("f,f","爷爷");relation("f,m","奶奶");relation("m,f","外公");relation("m,m","外婆");
        relation("f,ob","伯父");relation("f,lb","叔父");relation("m,ob","舅舅");relation("m,lb","舅舅");relation("f,os","姑妈");relation("m,ls","姨妈");
        relation("f,ob,w","伯母");relation("f,lb,w","婶婶");relation("m,ob,w","舅妈");relation("m,os,h","姨父");relation("f,ls,h","姑父");
        relation("ob,w","嫂子");relation("lb,w","弟媳");relation("os,h","姐夫");relation("ls,h","妹夫");
        relation("ob,s","侄子");relation("lb,d","侄女");relation("os,s","外甥");relation("ls,d","外甥女");
        relation("s,w","儿媳");relation("d,h","女婿");relation("s,s","孙子");relation("s,d","孙女");relation("d,s","外孙");relation("d,d","外孙女");
        relation(KinshipEngine.Sex.FEMALE,"h,f","公公");relation(KinshipEngine.Sex.FEMALE,"h,m","婆婆");relation(KinshipEngine.Sex.MALE,"w,f","岳父");relation(KinshipEngine.Sex.MALE,"w,m","岳母");
        relation("f,ob,s","堂哥");relation("f,lb,d","堂姐");relation("m,ob,s","表哥");relation("m,ls,d","表姐");relation("f,os,s","表哥");
        relation("f,xb","伯父");relation("f,xb","叔父");relation("m,xb","舅舅");relation("xs,s","外甥");
        relation(KinshipEngine.Sex.MALE,"f,s","自己");relation(KinshipEngine.Sex.FEMALE,"f,s","哥哥");relation(KinshipEngine.Sex.UNKNOWN,"m,d","自己");
        relation(KinshipEngine.Sex.MALE,"w,h","自己");relation(KinshipEngine.Sex.FEMALE,"h,w,f","爸爸");
        relation("f,w","继母");relation("f,f,f","曾祖父");relation("s,w,f","亲家公");relation("d,h,m","亲家母");
        kinshipChecks++;check(!KinshipEngine.calculate(KinshipEngine.Sex.UNKNOWN,List.of("alien")).supported,"unknown token refused");
        kinshipChecks++;check(!KinshipEngine.calculate(KinshipEngine.Sex.UNKNOWN,List.of("f","f","f","f","f")).supported,"complex chain explicit");
        kinshipChecks++;check(!KinshipEngine.calculate(KinshipEngine.Sex.MALE,List.of("h")).supported,"unsupported spouse model explicit");
        kinshipChecks++;check(!KinshipEngine.calculate(KinshipEngine.Sex.UNKNOWN,List.of("f","h")).supported,"current-person sex validation");
        KinshipEngine.Chain chain=new KinshipEngine.Chain();chain.add("f");chain.add("ob");chain.undo();kinshipChecks++;check(chain.values().equals(List.of("f")),"undo last relationship");chain.clear();kinshipChecks++;check(chain.values().isEmpty(),"clear relationship chain");chain.undo();check(chain.values().isEmpty(),"undo empty safe");
        for(int i=0;i<8;i++)check(chain.add("f"),"chain accepts bounded steps");check(!chain.add("f"),"chain bound");
        ZoneId zone=ZoneId.of("Asia/Shanghai");ReminderRules.Config c=new ReminderRules.Config();c.enabled=true;
        check(ReminderRules.next(c,at("2026-10-26T08:01"),zone)==at("2026-10-26T09:00"),"water next anchored interval");
        check(ReminderRules.next(c,at("2026-10-26T22:00"),zone)==at("2026-10-27T08:00"),"overnight quiet end");
        c.daily=true;c.dailyMinute=900;check(ReminderRules.next(c,at("2026-10-26T08:01"),zone)==at("2026-10-26T15:00"),"fruit daily time independent");
        check(ReminderRules.next(c,at("2026-10-26T15:00"),zone)==at("2026-10-27T15:00"),"daily boundary no duplicate");
        c.skipDate="2026-10-26";check(ReminderRules.next(c,at("2026-10-26T08:00"),zone)==at("2026-10-27T15:00"),"skip today");c.skipDate="";
        c.dailyMinute=1380;check(ReminderRules.next(c,at("2026-10-26T08:00"),zone)==-1,"daily quiet conflict no false schedule");
        c.snoozeMinutes=10;check(ReminderRules.snooze(c,at("2026-10-26T21:55"),zone)==at("2026-10-27T08:00"),"snooze respects quiet window");
        c.enabled=false;check(ReminderRules.next(c,at("2026-10-26T08:00"),zone)==-1,"disabled no schedule");
        c.enabled=true;c.daily=false;c.startMinute=1200;c.endMinute=120;c.quietStart=0;c.quietEnd=0;
        check(ReminderRules.next(c,at("2026-10-26T23:01"),zone)==at("2026-10-27T00:00"),"overnight active window");
        check(ReminderRules.inWindow(480,480,1320),"start inclusive");check(!ReminderRules.inWindow(1320,480,1320),"end exclusive");check(!ReminderRules.quiet(100,0,0),"equal quiet disables");
        check(ReminderRules.quiet(1439,1320,480),"quiet before midnight");check(ReminderRules.quiet(0,1320,480),"quiet after midnight");check(!ReminderRules.quiet(480,1320,480),"quiet ends at exact morning boundary");
        check(ReminderRules.fresh(at("2026-10-26T20:00"),at("2026-10-26T22:00")),"pending exact expiry boundary");
        check(!ReminderRules.fresh(at("2026-10-26T20:00"),at("2026-10-26T22:00")+1),"old notification expired");
        check(!ReminderRules.fresh(at("2026-10-26T20:00"),at("2026-10-26T19:59")),"clock rollback future pending rejected");
        check(!ReminderRules.fresh(0,at("2026-10-26T20:00")),"cleared pending rejected");
        c.startMinute=0;c.endMinute=0;c.snoozeMinutes=10;
        check(ReminderRules.snooze(c,at("2026-10-26T23:55"),zone)==at("2026-10-27T00:05"),"snooze crosses local date");
        check(ReminderRules.snooze(c,at("2026-10-26T23:55:30"),zone)==at("2026-10-27T00:06"),"snooze never shorter than configured minutes");
        c.quietStart=1320;c.quietEnd=480;check(ReminderRules.snooze(c,at("2026-10-26T23:55"),zone)==at("2026-10-27T08:00"),"overnight snooze moves to quiet end");
        c.enabled=false;check(ReminderRules.snooze(c,at("2026-10-26T23:55"),zone)==-1,"cleared configuration cannot snooze");
        check(!ThursdayCalendar.isThursday(clock("2026-10-28T15:59:59Z","Asia/Shanghai")),"Wednesday before midnight");
        check(ThursdayCalendar.isThursday(clock("2026-10-28T16:00:00Z","Asia/Shanghai")),"Thursday midnight");
        check(ThursdayCalendar.isThursday(clock("2026-10-29T15:59:59Z","Asia/Shanghai")),"Thursday before Friday");
        check(!ThursdayCalendar.isThursday(clock("2026-10-29T16:00:00Z","Asia/Shanghai")),"Friday midnight");
        check(ThursdayCalendar.daysUntil(clock("2026-10-29T16:00:00Z","Asia/Shanghai"))==6,"Friday six days away");
        check(ThursdayCalendar.isThursday(clock("2026-10-29T16:00:00Z","America/Los_Angeles")),"timezone same instant different local day");
        check(ThursdayCalendar.daysUntil(clock("2026-10-28T15:00:00Z","Asia/Shanghai"))==1,"Wednesday one day");
        check(ThursdayCalendar.daysUntil(clock("2026-10-28T16:00:00Z","Asia/Shanghai"))==0,"Thursday zero days");
        System.out.println("PASS "+checks+" assertions; "+kinshipChecks+" substantive kinship cases; "+KinshipEngine.rules().size()+" relationship table entries.");
    }
}
