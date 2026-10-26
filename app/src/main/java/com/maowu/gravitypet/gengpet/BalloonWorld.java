package com.maowu.gravitypet.gengpet;

/** Bounded native soft-body world. Flat arrays keep substeps allocation-free. */
final class BalloonWorld {
    static final int CAPACITY=6,NODES=24;
    private static final int MOTION_DEPTH=4,RECOVERY_DEPTH=6;
    static final double STEP=1d/180,SKIN=.6;
    final BalloonBody[] bodies=new BalloonBody[CAPACITY];
    double width,height,minX,minY;
    double gravity=350,discardedSeconds;
    double floor,floorTarget;
    double floorFriction=60;
    double floorTransitionSeconds = .2;
    private double floorVelocity;
    final boolean[] contacts=new boolean[CAPACITY*CAPACITY];
    int size;
    long nextId=1,rollbacks,contactEvents,externalPoseRejects;
    long rejectedFan,rejectedBudget,rejectedSelf,rejectedPair;
    long refinedSteps,physicsAttempts,acceptedLeaves;double acceptedSeconds,rejectedSeconds,minAcceptedH=STEP;private double stepH=STEP;
    private final boolean[][] savedContacts=new boolean[RECOVERY_DEPTH+1][CAPACITY*CAPACITY];
    private final long[] savedContactEvents=new long[RECOVERY_DEPTH+1];
    private final double[][] savedVX=new double[RECOVERY_DEPTH+1][CAPACITY*NODES],savedVY=new double[RECOVERY_DEPTH+1][CAPACITY*NODES],savedSoftness=new double[RECOVERY_DEPTH+1][CAPACITY];
    private double accumulator;
    private long attemptStart;
    static final int MAX_ATTEMPTS_PER_CALL=32;
    long budgetExhaustions;
    private BalloonBody dragged;
    private final double[] dragWeights=new double[NODES],hitScratch=new double[2];
    private double dragX,dragY;
    private boolean dragObstructed;
    private final double[] safeX=new double[CAPACITY*NODES],safeY=new double[CAPACITY*NODES];
    private final double[] left=new double[CAPACITY],right=new double[CAPACITY],top=new double[CAPACITY],bottom=new double[CAPACITY];
    private final double[] lambda=new double[CAPACITY*CAPACITY*NODES],normalX=new double[lambda.length],normalY=new double[lambda.length];
    private final int[] edgeId=new int[lambda.length];
    // Per-step continuous broad phase: compute each swept endpoint/edge box once.
    private final double[] pointLeft=new double[CAPACITY*NODES],pointRight=new double[CAPACITY*NODES],pointTop=new double[CAPACITY*NODES],pointBottom=new double[CAPACITY*NODES];
    private final double[] edgeLeft=new double[CAPACITY*NODES],edgeRight=new double[CAPACITY*NODES],edgeTop=new double[CAPACITY*NODES],edgeBottom=new double[CAPACITY*NODES];

    BalloonWorld(double width,double height) {
        if(!Double.isFinite(width+height)||width<30||height<30)throw new IllegalArgumentException("Invalid world");
        this.width=width;this.height=height;
        floor=floorTarget=height;
    }

    void resize(double width, double height) {
        this.width=width;this.height=height;
        floor=floorTarget=height;floorVelocity=0;
    }
    /** Edges are absolute SCREEN coordinates, not View-local dimensions. */
    void viewport(double left,double top,double right,double bottom) {
        if(!Double.isFinite(left+top+right+bottom)||right-left<30||bottom-top<30)
            throw new IllegalArgumentException("Invalid viewport");
        minX=left;minY=top;resize(right,bottom);
    }
    void resetClock(){accumulator=0;}

    /** Change coordinates without respawning meshes or discarding momentum. */
    void rebase(int from, int to, double newWidth, double newHeight) {
        int turns=(to-from+4)%4;
        if(turns==0)return;
        double c=turns==2?-1:0, s=turns==1?-1:turns==3?1:0;
        for(int k=0;k<size;k++) {
            BalloonBody b=bodies[k];
            for(int i=0;i<b.count;i++) {
                double x=b.x[i]-width/2,y=b.y[i]-height/2;
                b.x[i]=c*x-s*y+newWidth/2;b.y[i]=s*x+c*y+newHeight/2;
                x=b.previousX[i]-width/2;y=b.previousY[i]-height/2;
                b.previousX[i]=c*x-s*y+newWidth/2;b.previousY[i]=s*x+c*y+newHeight/2;
                x=b.vx[i];y=b.vy[i];b.vx[i]=c*x-s*y;b.vy[i]=s*x+c*y;
            }
            double ax=b.accelerationX,ay=b.accelerationY;
            b.accelerationX=c*ax-s*ay;b.accelerationY=s*ax+c*ay;
        }
        double x=dragX-width/2,y=dragY-height/2;
        dragX=c*x-s*y+newWidth/2;dragY=s*x+c*y+newHeight/2;
        resize(newWidth,newHeight);
        java.util.Arrays.fill(contacts,false);
        save();
    }
    BalloonBody addNear(double x,double y,double radius,BalloonCage cage,double angle) {
        if(size>=CAPACITY||radius<4||radius*2+SKIN*2>Math.min(width-minX,floor-minY))return null;
        BalloonBody body=new BalloonBody(nextId,0,0,radius,cage);
        double cs=Math.cos(angle),sn=Math.sin(angle);
        double[] localX=new double[NODES],localY=new double[NODES];
        for(int i=0;i<NODES;i++){localX[i]=body.x[i]*cs-body.y[i]*sn;localY[i]=body.x[i]*sn+body.y[i]*cs;}
        for(int attempt=0;attempt<257;attempt++) {
            double distance=attempt==0?0:radius*.7*Math.sqrt(attempt),theta=attempt*2.399963229728653;
            double cx=Math.max(minX+radius+SKIN,Math.min(width-radius-SKIN,x+Math.cos(theta)*distance));
            double cy=Math.max(minY+radius+SKIN,Math.min(floor-radius-SKIN,y+Math.sin(theta)*distance));
            boolean valid=true;
            for(int i=0;i<NODES;i++){body.x[i]=body.previousX[i]=cx+localX[i];body.y[i]=body.previousY[i]=cy+localY[i];
                if(body.x[i]<minX+SKIN||body.x[i]>width-SKIN||body.y[i]<minY+SKIN||body.y[i]>floor-SKIN)valid=false;}
            for(int i=0;valid&&i<size;i++)if(conflict(body,bodies[i],SKIN))valid=false;
            if(valid){nextId++;bodies[size++]=body;save();return body;}
        }
        return null;
    }
    boolean touching(BalloonBody a,BalloonBody b) {
        int ai=-1,bi=-1;for(int i=0;i<size;i++){if(bodies[i]==a)ai=i;if(bodies[i]==b)bi=i;}
        return ai>=0&&bi>=0&&contacts[ai*CAPACITY+bi];
    }
    BalloonBody add(double x,double y,double radius) {
        return add(x,y,radius,BalloonCage.circle(NODES));
    }
    BalloonBody add(double x,double y,double radius,BalloonCage cage) {
        if(size==CAPACITY||radius<4||!Double.isFinite(x+y+radius)
                ||x-radius<minX+SKIN||x+radius>width-SKIN||y-radius<minY+SKIN||y+radius>height-SKIN)
            throw new IllegalArgumentException("No valid spawn space");
        if(cage.u.length!=NODES)throw new IllegalArgumentException("World requires 24 nodes");
        BalloonBody b=new BalloonBody(nextId,x,y,radius,cage);
        for(int i=0;i<size;i++)if(conflict(b,bodies[i],SKIN))throw new IllegalArgumentException("Occupied spawn");
        nextId++;bodies[size++]=b;save();return b;
    }
    void remove(long id) {
        if(dragged!=null&&dragged.id==id)dragged=null;
        for(int i=0;i<size;i++)if(bodies[i].id==id){System.arraycopy(bodies,i+1,bodies,i,size-i-1);bodies[--size]=null;save();return;}
    }
    /** Restore a previously accepted body after a failed resize/reconfiguration transaction. */
    void restoreBody(BalloonBody body) {
        if(size>=CAPACITY)throw new IllegalStateException("Six-pet capacity");
        bodies[size++]=body;save();
    }
    boolean beginDrag(double x,double y) {
        return beginDrag(0,x,y);
    }
    boolean beginDrag(long selectedId,double x,double y) {
        for(int k=size-1;k>=0;k--) {
            BalloonBody b=bodies[k];if(selectedId!=0&&b.id!=selectedId)continue;
            int i=b.hitUV(x,y,hitScratch);if(i<0)continue;
            int j=(i+1)%NODES;double cx=b.centerX(),cy=b.centerY(),den=cross(cx,cy,b.x[i],b.y[i],b.x[j],b.y[j]);
            double a=cross(x,y,b.x[i],b.y[i],b.x[j],b.y[j])/den;
            double v=cross(cx,cy,x,y,b.x[j],b.y[j])/den;
            java.util.Arrays.fill(dragWeights,a/NODES);dragWeights[i]+=v;dragWeights[j]+=1-a-v;
            dragged=b;dragTo(x,y);return true;
        }
        return false;
    }
    void dragTo(double x,double y){if(Double.isFinite(x+y)){dragX=Math.max(minX+SKIN,Math.min(width-SKIN,x));dragY=Math.max(minY+SKIN,Math.min(height-SKIN,y));}}
    void endDrag(){dragged=null;}
    // Position control moves the whole body before contacts, independent of mass or grip location.
    // Reserve motion for the contact solver; the existing sweep and inversion checks still apply.
    private void prepareDragVelocity() {
        dragObstructed=false;
        if(dragged==null)return;
        bounds();int held=-1;for(int k=0;k<size;k++)if(bodies[k]==dragged)held=k;
        double gripX=0,gripY=0;for(int i=0;i<NODES;i++){gripX+=dragWeights[i]*dragged.x[i];gripY+=dragWeights[i]*dragged.y[i];}
        double towardX=dragX-gripX,towardY=dragY-gripY;
        double margin=dragged.radius*.6;
        for(int k=0;k<size;k++)if(k!=held && overlap(held,k,margin)){dragObstructed=true;break;}
        if((left[held]<minX+SKIN+margin&&towardX<-.01)||(right[held]>width-SKIN-margin&&towardX>.01)||(top[held]<minY+SKIN+margin&&towardY<-.01)||(bottom[held]>floor-SKIN-margin&&towardY>.01))dragObstructed=true;
        if(dragObstructed)return;
        BalloonBody b=dragged;double x=0,y=0;
        for(int i=0;i<NODES;i++){x+=dragWeights[i]*b.x[i];y+=dragWeights[i]*b.y[i];}
        double vx=(dragX-x)/stepH,vy=(dragY-y)/stepH;
        double speed=length(vx,vy),limit=Math.min(720,b.radius*.14/stepH);
        if(speed>limit){vx*=limit/speed;vy*=limit/speed;}
        for(int i=0;i<NODES;i++)if(b.inverseMass[i]>0){b.vx[i]=vx;b.vy[i]=vy;}
    }
    // Near obstacles, a compliant local grip allows the contact mesh to yield safely.
    private void solveContactDrag(){
        if(dragged==null||!dragObstructed)return;
        BalloonBody b=dragged;double x=0,y=0,den=1e-5/(stepH*stepH);
        for(int i=0;i<NODES;i++){x+=dragWeights[i]*b.x[i];y+=dragWeights[i]*b.y[i];if(b.inverseMass[i]>0)den+=dragWeights[i]*dragWeights[i];}
        double dx=dragX-x,dy=dragY-y,len=length(dx,dy),limit=b.radius*.03;
        if(len>limit){dx*=limit/len;dy*=limit/len;}
        double scale=1;
        for(int i=0;i<NODES;i++){
            if(b.inverseMass[i]==0)continue;
            double motion=length(dx,dy)*Math.abs(dragWeights[i])/den;
            if(motion>1e-9)scale=Math.min(scale,Math.max(0,b.radius*.14-length(b.x[i]-b.previousX[i],b.y[i]-b.previousY[i]))/motion);
        }
        for(int i=0;i<NODES;i++)if(b.inverseMass[i]>0){double weight=dragWeights[i]*scale/den;b.x[i]+=weight*dx;b.y[i]+=weight*dy;}
    }
    // Sweep the held cage along the pointer path without running the whole world hundreds
    // of times. Each short translation is collision-checked; blocked motion stays at contact.
    private void sweepDragTarget(){
        if(dragged==null)return;
        BalloonBody b=dragged;for(double inverse:b.inverseMass)if(inverse==0)return;
        double gx=0,gy=0;for(int i=0;i<NODES;i++){gx+=dragWeights[i]*b.x[i];gy+=dragWeights[i]*b.y[i];}
        double dx=dragX-gx,dy=dragY-gy,remaining=length(dx,dy);
        if(remaining<1e-7)return;
        double stride=b.radius*.1;int steps=Math.min(512,(int)Math.ceil(remaining/stride));
        double move=Math.min(remaining,steps*stride);dx=dx/remaining*move/steps;dy=dy/remaining*move/steps;
        boolean moved=false;
        for(int step=0;step<steps;step++){
            boolean blocked=false;
            for(int i=0;i<NODES;i++){b.previousX[i]=b.x[i];b.previousY[i]=b.y[i];b.x[i]+=dx;b.y[i]+=dy;
                if(b.x[i]<minX+SKIN||b.x[i]>width-SKIN||b.y[i]<minY+SKIN||b.y[i]>floor-SKIN)blocked=true;}
            for(int k=0;k<size&&!blocked;k++){
                BalloonBody other=bodies[k];if(other==b)continue;
                // conflict supplies an AABB broad phase; clearance also covers each short sweep.
                if(conflict(b,other,SKIN+length(dx,dy)))blocked=true;
            }
            if(blocked){System.arraycopy(b.previousX,0,b.x,0,NODES);System.arraycopy(b.previousY,0,b.y,0,NODES);break;}
            moved=true;
        }
        // Translation cannot change the fan or area; each accepted corridor had clearance
        // exceeding its step length, so a static edge cannot be skipped between samples.
        System.arraycopy(b.x,0,b.previousX,0,NODES);System.arraycopy(b.y,0,b.previousY,0,NODES);
        if(moved){java.util.Arrays.fill(b.vx,0);java.util.Arrays.fill(b.vy,0);save();}
    }
    void step(double dt) {
        attemptStart=physicsAttempts;
        java.util.Arrays.fill(contacts,false);
        if(!Double.isFinite(dt)||dt<0)throw new IllegalArgumentException("Invalid clock");
        for(int k=0;k<size;k++)for(int i=0;i<NODES;i++)if(bodies[k].x[i]!=safeX[k*NODES+i]||bodies[k].y[i]!=safeY[k*NODES+i]) {
            externalPoseRejects++;restore();k=size;break;
        }
        if(dt>0){sweepDragTarget();}
        double accepted=Math.min(dt,1d/30);discardedSeconds+=dt-accepted;accumulator+=accepted;
        double target=Math.max(minY+30,Math.min(height,floorTarget));
        // OEMs may report only the final IME height. Bound that jump using the
        // platform animation duration, then move the plane inside physics steps.
        // Contact corrections become velocity in finish(), so only touching
        // bodies are launched and retain momentum when the keyboard stops.
        double speed=Math.max(height-target,height-floor)/Math.max(STEP,floorTransitionSeconds);
        floorVelocity=accepted>0?Math.max(-speed,Math.min(speed,(target-floor)/accepted)):0;
        while(accumulator+1e-10>=STEP){accumulator-=STEP;substep();}
    }
    private void substep(){advance(STEP,0);}
    private boolean needsFloorRefinement(double h) {
        for(int k=0;k<size;k++)if(Math.abs(floorVelocity)*h>bodies[k].radius*.1)return true;
        return false;
    }
    private boolean needsMotionRefinement(double h){
        for(int k=0;k<size;k++){
            BalloonBody b=bodies[k];double acceleration=b.driven?length(b.accelerationX,b.accelerationY):Math.abs(gravity);
            double budget=b.radius*.1/h-acceleration*h;
            if(budget<0)return true;
            for(int i=0;i<NODES;i++)if(b.vx[i]*b.vx[i]+b.vy[i]*b.vy[i]>budget*budget)return true;
        }
        return false;
    }
    private void advance(double h,int depth){
        if(physicsAttempts-attemptStart>=MAX_ATTEMPTS_PER_CALL){budgetExhaustions++;rejectedSeconds+=h;discardedSeconds+=h;return;}
        if(depth<MOTION_DEPTH&&(needsMotionRefinement(h)||needsFloorRefinement(h))){refinedSteps++;advance(h*.5,depth+1);advance(h*.5,depth+1);return;}
        System.arraycopy(contacts,0,savedContacts[depth],0,contacts.length);savedContactEvents[depth]=contactEvents;
        for(int k=0;k<size;k++){BalloonBody b=bodies[k];System.arraycopy(b.vx,0,savedVX[depth],k*NODES,NODES);System.arraycopy(b.vy,0,savedVY[depth],k*NODES,NODES);savedSoftness[depth][k]=b.currentSoftness();}
        double oldFloor=floor;
        double target=Math.max(minY+30,Math.min(height,floorTarget));
        floor=floorVelocity<0?Math.max(target,floor+floorVelocity*h):Math.min(target,floor+floorVelocity*h);
        stepH=h;
        if(trySubstep()){acceptedSeconds+=h;acceptedLeaves++;minAcceptedH=Math.min(minAcceptedH,h);return;}
        System.arraycopy(savedContacts[depth],0,contacts,0,contacts.length);contactEvents=savedContactEvents[depth];
        // A rejected collision step must also roll back its moving boundary.
        floor=oldFloor;
        restore();
        for(int k=0;k<size;k++){BalloonBody b=bodies[k];System.arraycopy(savedVX[depth],k*NODES,b.vx,0,NODES);System.arraycopy(savedVY[depth],k*NODES,b.vy,0,NODES);b.restoreSoftness(savedSoftness[depth][k]);}
        // Only rejected collision sweeps use the extra recovery levels; ordinary motion
        // retains its bounded step count and every accepted leaf still passes validStep.
        if(depth<RECOVERY_DEPTH){refinedSteps++;advance(h*.5,depth+1);advance(h*.5,depth+1);}else{rollbacks++;rejectedSeconds+=h;restore();}
    }
    private boolean trySubstep() {
        physicsAttempts++;
        for(int k=0;k<size;k++){int start=k*CAPACITY*NODES,end=start+size*NODES;java.util.Arrays.fill(lambda,start,end,0);java.util.Arrays.fill(edgeId,start,end,-1);}
        prepareDragVelocity();
        for(int k=0;k<size;k++){BalloonBody b=bodies[k];b.predict(stepH*(b==dragged?1:b.timeScale),b==dragged&&!dragObstructed?0:(b.driven?b.accelerationX:0),b==dragged&&!dragObstructed?0:(b.driven?b.accelerationY:gravity),5000);}
        for(int it=0;it<7;it++) {
            for(int k=0;k<size;k++)bodies[k].solveShape(stepH*(bodies[k]==dragged?1:bodies[k].timeScale));
            solveContactDrag();
            if(dragged!=null)dragged.solveFan();
            bounds();
            for(int a=0;a<size;a++)for(int b=a+1;b<size;b++)if(overlap(a,b,SKIN*2)) {
                if(it%2==0){projectPair(a,b);projectPair(b,a);}else{projectPair(b,a);projectPair(a,b);}
            }
            for(int k=0;k<size;k++) {
                BalloonBody b=bodies[k];b.solveFan();b.solvePlane(1,0,minX+SKIN);b.solvePlane(-1,0,-width+SKIN);
                b.solvePlane(0,1,minY+SKIN);b.solvePlane(0,-1,-floor+SKIN);
                if(b!=dragged&&Double.isFinite(b.perchY)&&b.centerX()>b.perchLeft+b.radius*.3&&b.centerX()<b.perchRight-b.radius*.3){
                    double previousBottom=0;for(double py:b.previousY)previousBottom=Math.max(previousBottom,py);
                    if(previousBottom<=b.perchY+SKIN)b.solvePlane(0,-1,-b.perchY);
                }
            }
        }
        if(!validStep())return false;
        double frictionFactor=Math.exp(-Math.max(0,floorFriction)*stepH);
        for(int k=0;k<size;k++) {
            BalloonBody b=bodies[k];b.finish(stepH*(b==dragged?1:b.timeScale));
            if(b.inelastic){
                double vx=0,vy=0;boolean ground=false,ceiling=false,wall=false;
                for(int i=0;i<NODES;i++){vx+=b.vx[i];vy+=b.vy[i];ground|=b.y[i]>=floor-SKIN-.1;ceiling|=b.y[i]<=minY+SKIN+.1;wall|=b.x[i]<=minX+SKIN+.1||b.x[i]>=width-SKIN-.1;}
                vx/=NODES;vy/=NODES;
                for(int other=0;other<size;other++)if(other!=k&&contacts[k*CAPACITY+other]){vx=0;vy=0;break;}
                if(ground||ceiling)vy=0;if(wall)vx=0;
                // Clay dissipates deformation energy instead of springing back.
                for(int i=0;i<NODES;i++){b.vx[i]=vx;b.vy[i]=vy;}
            }
            for(int i=0;i<NODES;i++){
                if(b.y[i]>=floor-SKIN-.05||b.y[i]<=minY+SKIN+.05)b.vx[i]*=frictionFactor;
                if(b.x[i]<=minX+SKIN+.05||b.x[i]>=width-SKIN-.05)b.vy[i]*=frictionFactor;
            }
        }
        solveFriction();
        for(int k=0;k<size;k++)if(bodies[k]!=dragged)solveRestingWallFriction(bodies[k]);
        save();return true;
    }
    /** Low-speed whole-body static friction prevents a recovered asymmetric cage skating. */
    private void solveRestingWallFriction(BalloonBody b){
        double ax=b.driven?b.accelerationX:0,ay=b.driven?b.accelerationY:gravity,vx=0,vy=0;
        boolean horizontal=false,vertical=false;
        for(int i=0;i<NODES;i++){
            vx+=b.vx[i];vy+=b.vy[i];
            horizontal|=(ay>0&&b.y[i]>=floor-SKIN-.05)||(ay<0&&b.y[i]<=minY+SKIN+.05);
            vertical|=(ax<0&&b.x[i]<=minX+SKIN+.05)||(ax>0&&b.x[i]>=width-SKIN-.05);
        }
        vx/=NODES;vy/=NODES;double dx=0,dy=0,slow=b.radius*.15;
        if(horizontal&&Math.abs(vx)<slow)dx=-Math.copySign(Math.min(Math.abs(vx),.45*Math.abs(ay)*stepH),vx);
        if(vertical&&Math.abs(vy)<slow)dy=-Math.copySign(Math.min(Math.abs(vy),.45*Math.abs(ax)*stepH),vy);
        for(int i=0;i<NODES;i++)if(b.inverseMass[i]>0){b.vx[i]+=dx;b.vy[i]+=dy;}
    }
    /** Coulomb friction at actual node-edge contacts, including piles against side walls. */
    private void solveFriction(){
        for(int ai=0;ai<size;ai++)for(int bi=0;bi<size;bi++)if(ai!=bi&&contacts[ai*CAPACITY+bi]){
            BalloonBody a=bodies[ai],b=bodies[bi];
            for(int i=0;i<NODES;i++){
                int key=(ai*CAPACITY+bi)*NODES+i,e=edgeId[key];if(e<0||lambda[key]<=0)continue;
                int f=(e+1)%NODES;double dx=b.x[f]-b.x[e],dy=b.y[f]-b.y[e],len=dx*dx+dy*dy;
                if(len<1e-10)continue;
                double t=Math.max(0,Math.min(1,((a.x[i]-b.x[e])*dx+(a.y[i]-b.y[e])*dy)/len)),s=1-t;
                double den=a.inverseMass[i]+b.inverseMass[e]*s*s+b.inverseMass[f]*t*t;if(den<1e-10)continue;
                double tx=-normalY[key],ty=normalX[key];
                double relative=(a.vx[i]-s*b.vx[e]-t*b.vx[f])*tx+(a.vy[i]-s*b.vy[e]-t*b.vy[f])*ty;
                double limit=.45*lambda[key]/stepH,impulse=Math.max(-limit,Math.min(limit,-relative/den));
                a.vx[i]+=a.inverseMass[i]*impulse*tx;a.vy[i]+=a.inverseMass[i]*impulse*ty;
                b.vx[e]-=b.inverseMass[e]*s*impulse*tx;b.vy[e]-=b.inverseMass[e]*s*impulse*ty;
                b.vx[f]-=b.inverseMass[f]*t*impulse*tx;b.vy[f]-=b.inverseMass[f]*t*impulse*ty;
            }
        }
    }
    private void projectPair(int ai,int bi) {
        BalloonBody a=bodies[ai],b=bodies[bi];
        double loX=Double.POSITIVE_INFINITY,hiX=-loX,loY=loX,hiY=-loX;
        for(int i=0;i<NODES;i++){loX=Math.min(loX,b.x[i]);hiX=Math.max(hiX,b.x[i]);loY=Math.min(loY,b.y[i]);hiY=Math.max(hiY,b.y[i]);}
        for(int i=0;i<NODES;i++) {
            double px=a.x[i],py=a.y[i],best=Double.POSITIVE_INFINITY,tBest=0,qx=0,qy=0;int edge=0;
            // Exact target box, expanded after every changed endpoint below. A node
            // outside SKIN cannot be inside this polygon or close enough for contact.
            if(px<loX-SKIN||px>hiX+SKIN||py<loY-SKIN||py>hiY+SKIN)continue;
            for(int e=0;e<NODES;e++) {
                int f=(e+1)%NODES;double dx=b.x[f]-b.x[e],dy=b.y[f]-b.y[e],len=dx*dx+dy*dy;
                if(len<1e-12)continue;
                double t=Math.max(0,Math.min(1,((px-b.x[e])*dx+(py-b.y[e])*dy)/len));
                double x=b.x[e]+dx*t,y=b.y[e]+dy*t,d=(px-x)*(px-x)+(py-y)*(py-y);
                if(d<best){best=d;tBest=t;qx=x;qy=y;edge=e;}
            }
            boolean in=inside(px,py,b);double distance=Math.sqrt(best);if(!in&&distance>=SKIN)continue;
            int f=(edge+1)%NODES;double nx,ny;
            if(distance>1e-8){nx=(px-qx)/distance*(in?-1:1);ny=(py-qy)/distance*(in?-1:1);}
            else{double dx=b.x[f]-b.x[edge],dy=b.y[f]-b.y[edge],len=length(dx,dy);if(len<1e-9)continue;nx=dy/len;ny=-dx/len;}
            double s=1-tBest,den=a.inverseMass[i]+b.inverseMass[edge]*s*s+b.inverseMass[f]*tBest*tBest;
            if(den<1e-9)continue;int key=(ai*CAPACITY+bi)*NODES+i;
            if(edgeId[key]!=edge||normalX[key]*nx+normalY[key]*ny<.95)lambda[key]=0;
            edgeId[key]=edge;normalX[key]=nx;normalY[key]=ny;
            double c=(in?-distance:distance)-SKIN,next=Math.max(0,lambda[key]-c/den),dl=next-lambda[key];lambda[key]=next;
            a.x[i]+=a.inverseMass[i]*dl*nx;a.y[i]+=a.inverseMass[i]*dl*ny;
            b.x[edge]-=b.inverseMass[edge]*s*dl*nx;b.y[edge]-=b.inverseMass[edge]*s*dl*ny;
            b.x[f]-=b.inverseMass[f]*tBest*dl*nx;b.y[f]-=b.inverseMass[f]*tBest*dl*ny;contactEvents++;
            loX=Math.min(loX,Math.min(b.x[edge],b.x[f]));hiX=Math.max(hiX,Math.max(b.x[edge],b.x[f]));
            loY=Math.min(loY,Math.min(b.y[edge],b.y[f]));hiY=Math.max(hiY,Math.max(b.y[edge],b.y[f]));
            contacts[ai*CAPACITY+bi]=contacts[bi*CAPACITY+ai]=true;
        }
    }
    private boolean validStep() {
        for(int k=0;k<size;k++) {
            BalloonBody b=bodies[k];if(!b.positiveFan()){rejectedFan++;return false;}
            for(int i=0;i<NODES;i++) {
                double dx=b.x[i]-safeX[k*NODES+i],dy=b.y[i]-safeY[k*NODES+i],limit=b.radius*.15+1e-6;
                if(b.x[i]<minX+SKIN-1e-6||b.x[i]>width-SKIN+1e-6||b.y[i]<minY+SKIN-1e-6||b.y[i]>floor-SKIN+1e-6
                        ||dx*dx+dy*dy>limit*limit){rejectedBudget++;return false;}
                int n=k*NODES+i;
                pointLeft[n]=Math.min(b.previousX[i],b.x[i]);pointRight[n]=Math.max(b.previousX[i],b.x[i]);
                pointTop[n]=Math.min(b.previousY[i],b.y[i]);pointBottom[n]=Math.max(b.previousY[i],b.y[i]);
            }
            for(int i=0;i<NODES;i++){
                int n=k*NODES+i,j=k*NODES+(i+1)%NODES;
                edgeLeft[n]=Math.min(pointLeft[n],pointLeft[j])-1e-8;edgeRight[n]=Math.max(pointRight[n],pointRight[j])+1e-8;
                edgeTop[n]=Math.min(pointTop[n],pointTop[j])-1e-8;edgeBottom[n]=Math.max(pointBottom[n],pointBottom[j])+1e-8;
            }
            for(int i=0;i<NODES;i++)for(int e=0;e<NODES;e++)if(i!=e&&i!=(e+1)%NODES&&sweepCandidate(k,i,k,e)&&swept(b,i,b,e)){rejectedSelf++;return false;}
        }
        bounds();
        for(int a=0;a<size;a++)for(int b=a+1;b<size;b++) {
            // Spawn is validated and every accepted boundary trajectory is checked
            // below. Starting disjoint, containment/intersection cannot appear
            // without a boundary crossing; do not repeat all end-pose edge tests.
            // Swept broad phase covers both previous and proposed poses.
            BalloonBody x=bodies[a],y=bodies[b];boolean candidate=true;
            double xl=left[a],xr=right[a],xt=top[a],xb=bottom[a],yl=left[b],yr=right[b],yt=top[b],yb=bottom[b];
            for(int i=0;i<NODES;i++){xl=Math.min(xl,x.previousX[i]);xr=Math.max(xr,x.previousX[i]);xt=Math.min(xt,x.previousY[i]);xb=Math.max(xb,x.previousY[i]);
                yl=Math.min(yl,y.previousX[i]);yr=Math.max(yr,y.previousX[i]);yt=Math.min(yt,y.previousY[i]);yb=Math.max(yb,y.previousY[i]);}
            candidate=xl<=yr&&yl<=xr&&xt<=yb&&yt<=xb;
            if(candidate)for(int i=0;i<NODES;i++)for(int e=0;e<NODES;e++)
                if((sweepCandidate(a,i,b,e)&&swept(x,i,y,e))||(sweepCandidate(b,i,a,e)&&swept(y,i,x,e))){rejectedPair++;return false;}
        }
        return true;
    }
    private boolean sweepCandidate(int a,int i,int b,int e){
        int p=a*NODES+i,q=b*NODES+e;
        return pointLeft[p]<=edgeRight[q]&&pointRight[p]>=edgeLeft[q]&&pointTop[p]<=edgeBottom[q]&&pointBottom[p]>=edgeTop[q];
    }
    private static boolean swept(BalloonBody a,int i,BalloonBody b,int e) {
        int f=(e+1)%NODES;
        return BalloonSweep.crosses(a.previousX[i],a.previousY[i],a.x[i],a.y[i],
                b.previousX[e],b.previousY[e],b.x[e],b.y[e],b.previousX[f],b.previousY[f],b.x[f],b.y[f]);
    }
    private void save(){for(int k=0;k<size;k++){System.arraycopy(bodies[k].x,0,safeX,k*NODES,NODES);System.arraycopy(bodies[k].y,0,safeY,k*NODES,NODES);}}
    private void restore(){for(int k=0;k<size;k++){BalloonBody b=bodies[k];System.arraycopy(safeX,k*NODES,b.x,0,NODES);System.arraycopy(safeY,k*NODES,b.y,0,NODES);java.util.Arrays.fill(b.vx,0);java.util.Arrays.fill(b.vy,0);}}
    private void bounds(){for(int k=0;k<size;k++){left[k]=top[k]=Double.POSITIVE_INFINITY;right[k]=bottom[k]=Double.NEGATIVE_INFINITY;
        for(int i=0;i<NODES;i++){left[k]=Math.min(left[k],bodies[k].x[i]);right[k]=Math.max(right[k],bodies[k].x[i]);top[k]=Math.min(top[k],bodies[k].y[i]);bottom[k]=Math.max(bottom[k],bodies[k].y[i]);}}}
    private boolean overlap(int a,int b,double margin){return left[a]<=right[b]+margin&&left[b]<=right[a]+margin&&top[a]<=bottom[b]+margin&&top[b]<=bottom[a]+margin;}
    static boolean inside(double x,double y,BalloonBody b){boolean in=false;for(int i=0,j=NODES-1;i<NODES;j=i++)if((b.y[i]>y)!=(b.y[j]>y)&&x<(b.x[j]-b.x[i])*(y-b.y[i])/(b.y[j]-b.y[i])+b.x[i])in=!in;return in;}
    static boolean conflict(BalloonBody a,BalloonBody b,double clearance) {
        double al=Double.POSITIVE_INFINITY,ar=-al,at=al,ab=-al,bl=al,br=-al,bt=al,bb=-al;
        for(int i=0;i<NODES;i++){al=Math.min(al,a.x[i]);ar=Math.max(ar,a.x[i]);at=Math.min(at,a.y[i]);ab=Math.max(ab,a.y[i]);bl=Math.min(bl,b.x[i]);br=Math.max(br,b.x[i]);bt=Math.min(bt,b.y[i]);bb=Math.max(bb,b.y[i]);}
        if(al>br+clearance||bl>ar+clearance||at>bb+clearance||bt>ab+clearance)return false;
        for(int i=0;i<NODES;i++) {
            if(inside(a.x[i],a.y[i],b)||inside(b.x[i],b.y[i],a))return true;
            int j=(i+1)%NODES;
            for(int e=0;e<NODES;e++) {
                int f=(e+1)%NODES;
                if(segmentCross(a.x[i],a.y[i],a.x[j],a.y[j],b.x[e],b.y[e],b.x[f],b.y[f]))return true;
                if(clearance>0&&(segmentDistance(a.x[i],a.y[i],b.x[e],b.y[e],b.x[f],b.y[f])<=clearance
                        ||segmentDistance(b.x[e],b.y[e],a.x[i],a.y[i],a.x[j],a.y[j])<=clearance))return true;
            }
        }
        return false;
    }
    private static double cross(double ax,double ay,double bx,double by,double cx,double cy){return(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);}
    private static boolean segmentCross(double ax,double ay,double bx,double by,double cx,double cy,double dx,double dy){return cross(ax,ay,bx,by,cx,cy)*cross(ax,ay,bx,by,dx,dy)<-1e-8&&cross(cx,cy,dx,dy,ax,ay)*cross(cx,cy,dx,dy,bx,by)<-1e-8;}
    private static double segmentDistance(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay,len=dx*dx+dy*dy;if(len<1e-12)return length(x-ax,y-ay);double t=Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/len));return length(x-ax-dx*t,y-ay-dy*t);}
    private static double length(double x,double y){return Math.sqrt(x*x+y*y);}
}
