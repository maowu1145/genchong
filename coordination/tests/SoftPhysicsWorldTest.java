package com.maowu.gravitypet.gengpet;

import java.util.Arrays;

/** Standalone tests of the actual XPBD source and PhysicsWorld bridge, not Android mocks. */
public final class SoftPhysicsWorldTest {
  private static int checks;
  private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
  private static void near(double actual,double wanted,double tolerance,String message){check(Math.abs(actual-wanted)<=tolerance,message+": actual="+actual+", wanted="+wanted);}
  private static float[] ellipse(float cx,float cy,float rx,float ry){
    float[] uv=new float[48];for(int i=0;i<24;i++){double angle=i*Math.PI*2/24;uv[i*2]=cx+rx*(float)Math.cos(angle);uv[i*2+1]=cy+ry*(float)Math.sin(angle);}return uv;
  }
  private static final float[] SKIN=ellipse(.5f,.53f,.34f,.36f);
  private static PhysicsWorld world(){PhysicsWorld w=new PhysicsWorld();w.setBounds(12,24,628,720);w.setPixelsPerMeter(80);return w;}
  private static PhysicsWorld.Body pet(PhysicsWorld w,String id,float x,float y,float size,float softness){
    PhysicsWorld.Body b=new PhysicsWorld.Body(id,x,y,size*.42f);b.softness=softness;
    check(w.add(b),"body registered");w.configureSoft(b,SKIN,size);
    check(b.soft!=null,"real XPBD body configured: "+b.softFailure);return b;
  }
  private static void run(PhysicsWorld w,int steps){for(int i=0;i<steps;i++)w.step(PhysicsWorld.STEP);}
  private static void valid(PhysicsWorld.Body b){
    check(b.soft.validFan(),"mesh has positive, non-intersecting triangle fan");
    for(int i=0;i<b.soft.count;i++)check(Double.isFinite(b.soft.x[i]+b.soft.y[i])&&b.soft.x[i]>=12+BalloonWorld.SKIN-1e-5&&b.soft.x[i]<=628-BalloonWorld.SKIN+1e-5&&b.soft.y[i]>=24+BalloonWorld.SKIN-1e-5&&b.soft.y[i]<=720-BalloonWorld.SKIN+1e-5,"node obeys real screen planes");
  }
  private static double bottom(PhysicsWorld.Body b){return Arrays.stream(b.soft.y).max().getAsDouble();}
  private static double height(PhysicsWorld.Body b){return bottom(b)-Arrays.stream(b.soft.y).min().getAsDouble();}
  private static double maxSpeed(PhysicsWorld.Body b){double v=0;for(int i=0;i<b.soft.count;i++)v=Math.max(v,Math.hypot(b.soft.vx[i],b.soft.vy[i]));return v;}
  public static void main(String[] args) throws Exception {
    displayTimeAdvance();
    check(BalloonWorld.CAPACITY==6,"native capacity is six, not 192");
    near(PhysicsWorld.effectiveSoftness(.5f),.175,1e-8,"50% uses firmer material mapping");
    near(PhysicsWorld.effectiveSoftness(1),.7,1e-8,"maximum remains soft but bounded");
    check(PhysicsWorld.effectiveSoftness(.3f)<PhysicsWorld.effectiveSoftness(.5f),"new default 30% is firmer than preserved 50%");
    PhysicsWorld w=world();PhysicsWorld.Body b=pet(w,"one",320,180,120,.8f);
    BalloonBody initial=b.soft;double initialArea=initial.restArea;float initialY=b.y;
    run(w,30);check(b.y>initialY,"soft body falls with gravity");
    w.configureSoft(b,SKIN.clone(),120);check(b.soft==initial,"equal metadata preserves mesh identity");
    double[] saved=b.soft.x.clone();w.configureSoft(b,SKIN,120);check(Arrays.equals(saved,b.soft.x),"no-op configure preserves every deformed node");
    run(w,2400);valid(b);
    System.out.println("Single: y="+b.y+", bottom="+bottom(b)+", deformation="+b.deformation+", height="+height(b)+", speed="+maxSpeed(b)+", sleep="+b.sleeping+", rollbacks="+w.softRollbacks());
    near(bottom(b),720-BalloonWorld.SKIN,.08,"actual contour touches floor despite transparent padding");
    check(b.deformation>.002,"supported mesh retains load deformation");
    check(b.soft.area()>initialArea*.85&&b.soft.area()<initialArea*1.15,"XPBD conserves area under load");
    check(b.sleeping&&w.isSettled(),"supported quiet mesh sleeps with nonzero compression");
    long steps=w.softStepCount();float sleepingY=b.y;run(w,120);
    check(w.softStepCount()==steps,"sleeping soft world stops native simulation");near(b.y,sleepingY,1e-6,"sleep pose retained");
    w.setGravity(5,7);check(!b.sleeping,"tilt wakes soft world");run(w,30);check(b.x>320,"tilt moves cage in screen direction");

    w=world();b=pet(w,"pinned",250,200,100,.5f);b.pinned=true;run(w,12);
    saved=b.soft.x.clone();double[] savedY=b.soft.y.clone();run(w,120);
    check(Arrays.equals(saved,b.soft.x)&&Arrays.equals(savedY,b.soft.y),"pinned nodes remain exactly fixed");
    b.pinned=false;b.toolOpen=true;b.wake();run(w,60);
    check(Arrays.equals(saved,b.soft.x)&&Arrays.equals(savedY,b.soft.y),"tool-open freezes actual cage");
    b.toolOpen=false;b.wake();run(w,30);check(b.y>200,"closing tool resumes gravity");
    b.held=true;b.x=430;b.y=230;run(w,20);
    check(b.x>380,"drag moves native cage to requested screen center");valid(b);
    w.throwBody(b,-800,-500);run(w,12);check(b.vx<0&&b.vy<0,"release velocity is propagated to mesh nodes");valid(b);

    w=world();PhysicsWorld.Body a=pet(w,"low",320,580,260,.6f);
    w.configureSoft(a,new float[]{.15f,.15f,.85f,.15f,.85f,.85f,.15f,.85f},260);a.pinned=true;
    PhysicsWorld.Body top=pet(w,"top",320,350,120,.6f);
    run(w,3600);valid(a);valid(top);
    System.out.println("Stack: lower="+a.y+", upper="+top.y+", sleeps="+a.sleeping+"/"+top.sleeping+", speeds="+maxSpeed(a)+"/"+maxSpeed(top)+", rollbacks="+w.softRollbacks());
    check(!BalloonWorld.conflict(a.soft,top.soft,0),"two meshes do not intersect");
    check(a.sleeping&&top.sleeping,"supported stack enters group sleep");
    float oldTop=top.y;w.remove(a);run(w,180);
    check(top.y>oldTop+25,"removing support makes upper mesh fall");valid(top);

    w=world();w.setGravity(0,.5f);a=pet(w,"air-a",270,220,100,.5f);top=pet(w,"air-b",340,220,100,.5f);
    run(w,100);check(!a.sleeping&&!top.sleeping,"low-gravity airborne contact cannot create support sleep");

    w=world();b=pet(w,"size",320,200,120,.5f);initial=b.soft;
    w.configureSoft(b,SKIN,140);check(b.soft!=initial&&b.soft.radius==70,"size change rebuilds rest geometry once");
    initial=b.soft;w.configureSoft(b,new float[]{0,0,1,Float.NaN,1,1},140);
    check(b.soft==initial&&b.softFailure!=null,"invalid metadata preserves previous valid cage");
    float[] bounds=new float[4];check(w.softBounds(b,bounds)&&bounds[2]>bounds[0],"geometry bounds are available without rendering");
    b.edgeX=b.edgeY=10000;float px=b.x,py=b.y;w.constrain(b);near(b.x,px,0,"legacy window edge does not constrain native x");near(b.y,py,0,"legacy window edge does not constrain native y");

    w=world();for(int i=0;i<6;i++)pet(w,"six-"+i,150+i%3*150,170+i/3*140,110,.75f);
    check(!w.add(new PhysicsWorld.Body("seven",320,300,30)),"seventh rejected through bridge");
    long start=System.nanoTime();run(w,1200);double millis=(System.nanoTime()-start)/1e6;
    for(PhysicsWorld.Body p:w.bodies())valid(p);
    for(int i=0;i<6;i++)for(int j=i+1;j<6;j++)check(!BalloonWorld.conflict(w.bodies().get(i).soft,w.bodies().get(j).soft,0),"six meshes stay disjoint");
    check(w.softBudgetExhaustions()==0,"ordinary six-pet scene stays inside attempt budget");
    System.out.println("Six: 1200 bridge ticks desktop wall="+millis+"ms, attempts="+w.softPhysicsAttempts()+", rollbacks="+w.softRollbacks()+", settled="+w.isSettled());
    // Original boxob equal-load softness calibration, exercised without Android or the bridge.
    double hard=extension(0),soft=extension(1);
    check(soft>2*hard&&soft>1,"same load stretches soft endpoint more than rigid endpoint");
    System.out.println("Equal-load extension hard="+hard+", soft="+soft);
    w=world();b=new PhysicsWorld.Body("inverted",320,240,60);b.angle=(float)Math.PI;b.softness=.3f;
    check(w.add(b),"inverted pet added");w.configureSoft(b,SKIN,120);run(w,2400);
    System.out.println("Initially inverted: angle="+Math.toDegrees(b.angle)+", sleeping="+b.sleeping);
    check(Math.abs(b.angle)<Math.PI/6,"free inverted pet recovers upright without pose teleport");
    check(b.sleeping,"upright recovery eventually settles");
    if(args.length>0)phoneCatalog(args[0]);
    System.out.println("PASS: "+checks+" soft physics assertions");
  }
  private static void displayTimeAdvance(){
    PhysicsWorld w=world();w.setGravity(0,0);
    PhysicsWorld.Body b=pet(w,"display-clock",320,220,120,.3f);b.gravity=false;b.vx=120;
    long now=1_000_000_000L;
    check(w.advance(now)==0,"soft first display frame initializes clock only");
    check(w.advance(now+=16_666_667L)==1,"60 Hz display invokes soft bridge once");
    check(w.softStepCount()==1&&w.softPhysicsAttempts()==3,"60 Hz frame advances three native 180 Hz steps");
    check(w.advance(now+=33_333_333L)==1,"30 Hz display invokes soft bridge once");
    check(w.softStepCount()==2&&w.softPhysicsAttempts()==9,"30 Hz frame advances six native steps, not one legacy step: calls="+w.softStepCount()+", attempts="+w.softPhysicsAttempts());
    near(b.x,326,.1,"50 ms of display time produces 50 ms of motion");
    float before=b.x;
    check(w.advance(now+=1_000_000_000L)==1,"late display frame still invokes soft bridge once");
    check(w.softStepCount()==3&&w.softPhysicsAttempts()==15,"late frame is capped at 1/30 second");
    near(b.x-before,4,.1,"late frame cannot catch up a second of motion");
    check(w.advance(now)==0,"duplicate display timestamp does not simulate");
    w.resetClock();check(w.advance(now+5_000_000_000L)==0,"soft reset excludes paused elapsed time");
  }
  private static void phoneCatalog(String file) throws Exception {
    PhysicsWorld w=new PhysicsWorld();w.setBounds(0,0,1080,2408);w.setPixelsPerMeter(330);
    int index=0;
    for(String line:java.nio.file.Files.readAllLines(java.nio.file.Paths.get(file))){
      String[] parts=line.trim().split("\\s+");float[] uv=new float[parts.length-1];
      for(int i=0;i<uv.length;i++)uv[i]=Float.parseFloat(parts[i+1]);
      check(uv.length==48,"actual catalog supplies 24 contour points: "+parts[0]);
      groundRecovery(parts[0],uv,Math.PI);
      if(parts[0].equals("doro")||parts[0].equals("grudge_panda"))groundRecovery(parts[0],uv,Math.PI/2);
      PhysicsWorld.Body b=new PhysicsWorld.Body(parts[0],220+index%3*310,350+index/3*450,110);
      b.softness=.8f;check(w.add(b),"catalog body added");w.configureSoft(b,uv,264);
      check(b.soft!=null,"catalog cage fits phone: "+parts[0]+" "+b.softFailure);
      for(int i=0;i<24;i++){
        near(b.soft.restU[i],uv[i*2],0,"original catalog u retained");
        near(b.soft.restV[i],uv[i*2+1],0,"original catalog v retained");
      }
      double mass=0,weightedV=0;
      for(int i=0;i<24;i++){double m=1/b.soft.inverseMass[i];mass+=m;weightedV+=m*b.soft.restV[i];}
      check(weightedV/mass>b.soft.restCenterV+.03,"authored lower half shifts true mass centre downward");
      BalloonMesh mesh=new BalloonMesh(b.soft,512,512,0,0,1,1);
      double uvArea=0;
      for(int i=0;i<mesh.texture.length;i+=6){
        double ax=mesh.texture[i+2]-mesh.texture[i],ay=mesh.texture[i+3]-mesh.texture[i+1];
        double bx=mesh.texture[i+4]-mesh.texture[i],by=mesh.texture[i+5]-mesh.texture[i+1];
        uvArea+=Math.abs(ax*by-ay*bx)/2;
      }
      near(uvArea,512d*512,.2,"extended render fan covers full PNG without clipping");index++;
    }
    check(index==6,"actual six catalog characters exercised");
    long start=System.nanoTime(),worst=0;
    for(int i=0;i<2400;i++){
      long t=System.nanoTime(),before=w.softPhysicsAttempts();w.step(PhysicsWorld.STEP);worst=Math.max(worst,System.nanoTime()-t);
      check(w.softPhysicsAttempts()-before<=BalloonWorld.MAX_ATTEMPTS_PER_CALL,"bounded native attempts per tick");
    }
    double millis=(System.nanoTime()-start)/1e6;
    for(PhysicsWorld.Body b:w.bodies()){
      check(b.soft.validFan(),"phone catalog mesh remains valid");
      for(int i=0;i<24;i++)check(Double.isFinite(b.soft.x[i]+b.soft.y[i])&&b.soft.x[i]>=BalloonWorld.SKIN-1e-5&&b.soft.x[i]<=1080-BalloonWorld.SKIN+1e-5&&b.soft.y[i]>=BalloonWorld.SKIN-1e-5&&b.soft.y[i]<=2408-BalloonWorld.SKIN+1e-5,"phone node obeys physical planes");
    }
    for(int i=0;i<6;i++)for(int j=i+1;j<6;j++)check(!BalloonWorld.conflict(w.bodies().get(i).soft,w.bodies().get(j).soft,0),"real catalog cages do not intersect");
    System.out.println("Phone catalog: 2400 ticks desktop wall="+millis+"ms, worst="+(worst/1e6)+"ms, native steps="+w.softStepCount()+", attempts="+w.softPhysicsAttempts()+", rollbacks="+w.softRollbacks()+", budgetExhaustions="+w.softBudgetExhaustions()+", settled="+w.isSettled());
    check(w.softBudgetExhaustions()==0,"phone catalog normal settling stays inside budget");
    check(w.isSettled(),"phone catalog six-pet pile eventually sleeps");
    PhysicsWorld.Body dragged=w.bodies().get(0);dragged.held=true;float fromX=dragged.x,fromY=dragged.y;
    long beforeAttempts=w.softPhysicsAttempts(),dragStart=System.nanoTime();
    for(int frame=1;frame<=180;frame++){
      dragged.x=fromX+(540-fromX)*Math.min(1,frame/90f);
      dragged.y=fromY+(900-fromY)*Math.min(1,frame/90f);
      long frameAttempts=w.softPhysicsAttempts();w.step(PhysicsWorld.STEP);
      check(w.softPhysicsAttempts()-frameAttempts<=BalloonWorld.MAX_ATTEMPTS_PER_CALL,"drag attempts remain bounded");
      for(PhysicsWorld.Body p:w.bodies())check(p.soft.validFan(),"real contour remains valid while lifting out of pile");
    }
    check(dragged.y<fromY-300,"held real contour lifts out of sleeping pile");
    w.throwBody(dragged,2400,-1800);
    for(int i=0;i<600;i++){long attempts=w.softPhysicsAttempts();w.step(PhysicsWorld.STEP);check(w.softPhysicsAttempts()-attempts<=BalloonWorld.MAX_ATTEMPTS_PER_CALL,"fast throw attempts remain bounded");}
    for(int i=0;i<6;i++)for(int j=i+1;j<6;j++)check(!BalloonWorld.conflict(w.bodies().get(i).soft,w.bodies().get(j).soft,0),"real cages remain disjoint after drag and throw");
    System.out.println("Phone drag + throw: desktop wall="+(System.nanoTime()-dragStart)/1e6+"ms, additional attempts="+(w.softPhysicsAttempts()-beforeAttempts)+", total rollbacks="+w.softRollbacks()+", budgetExhaustions="+w.softBudgetExhaustions()+", discarded seconds="+w.softDiscardedSeconds());
    check(w.softDiscardedSeconds()<1d/60,"bounded fast-throw recovery loses less than one display frame of simulated time");
    w.setBounds(20,30,1060,2300);run(w,30);
    for(PhysicsWorld.Body p:w.bodies()){
      check(p.soft!=null&&p.soft.validFan(),"viewport change reconstructs a valid cage");
      for(int i=0;i<24;i++)check(p.soft.x[i]>=20+BalloonWorld.SKIN-1e-5&&p.soft.x[i]<=1060-BalloonWorld.SKIN+1e-5&&p.soft.y[i]>=30+BalloonWorld.SKIN-1e-5&&p.soft.y[i]<=2300-BalloonWorld.SKIN+1e-5,"viewport uses absolute inset screen planes");
    }
    for(PhysicsWorld.Body p:w.bodies())p.softness=.3f;
    w.setGravity(-9.81f,0);run(w,3600);
    System.out.println("Side wall: settled="+w.isSettled()+", attempts="+w.softPhysicsAttempts()+", rollbacks="+w.softRollbacks()+", budgetExhaustions="+w.softBudgetExhaustions());
    for(PhysicsWorld.Body p:w.bodies())System.out.println("  "+p.id+" angle="+Math.toDegrees(p.angle)+", speed="+maxSpeed(p)+", sleep="+p.sleeping);
    check(w.isSettled(),"real six-pet pile at side wall eventually sleeps");
  }
  private static void groundRecovery(String id,float[] uv,double angle){
    PhysicsWorld w=new PhysicsWorld();w.setBounds(0,0,1080,2408);w.setPixelsPerMeter(330);
    PhysicsWorld.Body b=new PhysicsWorld.Body(id,540,2275,110);b.angle=(float)angle;b.softness=.3f;
    check(w.add(b),"ground recovery body registered");w.configureSoft(b,uv,264);
    check(b.soft!=null,"real contour configured for ground recovery");
    b.sleeping=true;check(!w.isSettled(),"inverted sleeping state cannot stop display callbacks");
    long now=1_000_000_000L;w.advance(now);int frames=0;
    while(frames++<600){
      w.advance(now+=33_333_334L);
      check(b.soft.validFan()&&bottom(b)<=2408-BalloonWorld.SKIN+1e-5,"ground recovery preserves valid cage and floor collision");
      check(!b.sleeping||Math.abs(b.angle)<=Math.PI/6,"real contour never sleeps on its head or side");
      if(w.isSettled())break;
    }
    double sumV=0;for(int i=0;i<24;i++)sumV+=b.soft.vx[i]*b.soft.vx[i]+b.soft.vy[i]*b.soft.vy[i];
    System.out.println("Ground recovery "+id+" from="+Math.toDegrees(angle)+" final="+Math.toDegrees(b.angle)+", frames="+frames+", sleeping="+b.sleeping+", speed="+maxSpeed(b)+", rms="+Math.sqrt(sumV/24)+", mean="+Math.hypot(b.vx,b.vy)+", vx="+b.vx+", vy="+b.vy+", bottom="+bottom(b)+", rollbacks="+w.softRollbacks());
    check(Math.abs(b.angle)<=Math.PI/6&&b.sleeping,"real contour recovers and sleeps upright: "+id);
  }
  private static double extension(double softness){
    BalloonBody body=new BalloonBody(1,200,200,40,24);body.setSoftness(softness);body.inverseMass[12]=0;
    for(int frame=0;frame<720;frame++){
      body.predict(BalloonWorld.STEP,150,0,720);for(int i=0;i<7;i++)body.solveShape(BalloonWorld.STEP);body.finish(BalloonWorld.STEP);
    }
    check(body.validFan(),"equal-load mesh stays valid");return body.x[0]-body.x[12]-80;
  }
}

