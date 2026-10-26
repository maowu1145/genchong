package com.maowu.gravitypet.gengpet;

/** Local XPBD mesh used by the overlay world and the isolated regression lab. */
final class BalloonBody {
    final long id;
    final int count;
    final double radius, restArea;
    final double[] x,y,previousX,previousY,vx,vy,inverseMass;
    private double[] mobileInverseMass;
    final double[] restU,restV;
    final double restCenterU,restCenterV;
    private final double[] edgeRest,edgeLambda,areaGX,areaGY,restFanCross;
    private double areaLambda;
    long limitedVelocities;
    private double complianceScale=1;
    private double softness=.5,targetSoftness=.5;
    private boolean tuningInitialized;
    // Logarithmic range calibrated by equal-load displacement, not slider percentages.
    // Keep the rigid endpoint at 200; halve maximum compliance from 2 to 1.
    static double stiffness(double softness){return 200/Math.pow(200,Math.max(0,Math.min(1,Double.isFinite(softness)?softness:.5)));}
    boolean inelastic;
    boolean rigid; // Preserve the authored silhouette under drag and contact.
    void setSoftness(double value){
        targetSoftness=Math.max(0,Math.min(1,Double.isFinite(value)?value:.5));
        if(!tuningInitialized){softness=targetSoftness;tuningInitialized=true;complianceScale=1/stiffness(softness);}
    }
    double currentSoftness(){return softness;}
    void restoreSoftness(double value){softness=value;if(tuningInitialized)complianceScale=1/stiffness(value);}
    static double clampMass(double mass){return Math.max(.5,Math.min(10,Double.isFinite(mass)?mass:1));}
    void setMassMultiplier(double mass){
        double inverse=1/clampMass(mass);
        for(int i=0;i<count;i++)if(inverseMass[i]>0)inverseMass[i]=inverse;
    }
    /** Bottom-heavy authored material; normalize total mass so softness remains comparable. */
    void lowerCenterOfMass(){
        mobileInverseMass=new double[count];double lo=1,hi=0,total=0;
        for(double v:restV){lo=Math.min(lo,v);hi=Math.max(hi,v);}
        for(int i=0;i<count;i++){
            double t=(restV[i]-lo)/Math.max(1e-6,hi-lo),mass=.65+2.35*t*t;
            mobileInverseMass[i]=mass;total+=mass;
        }
        for(int i=0;i<count;i++)mobileInverseMass[i]=(total/count)/mobileInverseMass[i];
        setLocked(false);
    }
    void setLocked(boolean locked){
        if(locked)java.util.Arrays.fill(inverseMass,0);
        else if(mobileInverseMass!=null)System.arraycopy(mobileInverseMass,0,inverseMass,0,count);
        else java.util.Arrays.fill(inverseMass,1);
    }
    /** Gentle angular velocity control around the true mass centre; never rewrites a pose. */
    void stabilizeUpright(double dt,double target,double angle,boolean supported){
        double error=Math.atan2(Math.sin(target-angle),Math.cos(target-angle));
        double mass=0,cx=0,cy=0;
        for(int i=0;i<count;i++){if(inverseMass[i]<=0)return;double m=1/inverseMass[i];mass+=m;cx+=x[i]*m;cy+=y[i]*m;}
        cx/=mass;cy/=mass;double moment=0,spin=0;
        for(int i=0;i<count;i++){double m=1/inverseMass[i],px=x[i]-cx,py=y[i]-cy;moment+=m*(px*px+py*py);spin+=m*(px*vy[i]-py*vx[i]);}
        double omega=spin/Math.max(1,moment);
        double deadZone=supported?Math.PI/6:.14;
        if(Math.abs(error)<deadZone&&Math.abs(omega)<.12)return;
        boolean recovering=supported&&Math.abs(error)>deadZone;
        double desired=Math.abs(error)<deadZone?0:Math.max(-3,Math.min(3,error*2.4));
        double change=(desired-omega)*(1-Math.exp(-(recovering?24:supported?6:1.5)*dt));
        // The old fixed 8 rad/s^2 cap could not lift a broad head off the floor:
        // supporting gravity/friction scales with g/r. Boost only grounded recovery.
        double accelerationLimit=recovering?Math.max(8,2*Math.sqrt(accelerationX*accelerationX+accelerationY*accelerationY)/Math.max(12,radius)):8;
        change=Math.max(-accelerationLimit*dt,Math.min(accelerationLimit*dt,change));
        for(int i=0;i<count;i++){vx[i]-=change*(y[i]-cy);vy[i]+=change*(x[i]-cx);}
    }
    double perchLeft,perchRight,perchY=Double.POSITIVE_INFINITY;
    boolean driven;
    double timeScale=1;
    double accelerationX,accelerationY;

    BalloonBody(long id,double cx,double cy,double radius,int count) {
        this(id,cx,cy,radius,BalloonCage.circle(count));
    }

    /** cx/cy locate the texture centre; the node centroid may be asymmetric. */
    BalloonBody(long id,double cx,double cy,double radius,BalloonCage cage) {
        int count=cage.u.length;
        if(id<=0 || !Double.isFinite(cx+cy+radius) || radius<=0 || count<8 || count>32)
            throw new IllegalArgumentException("Invalid balloon geometry");
        this.id=id;this.radius=radius;this.count=count;
        restCenterU=cage.centerU;restCenterV=cage.centerV;
        x=new double[count];y=new double[count];previousX=new double[count];previousY=new double[count];
        vx=new double[count];vy=new double[count];inverseMass=new double[count];
        restU=new double[count];restV=new double[count];edgeRest=new double[count*2];edgeLambda=new double[count*2];
        areaGX=new double[count];areaGY=new double[count];restFanCross=new double[count];
        for(int i=0;i<count;i++) {
            double dx=(cage.u[i]-.5)*2*radius,dy=(cage.v[i]-.5)*2*radius;
            x[i]=previousX[i]=cx+dx;y[i]=previousY[i]=cy+dy;inverseMass[i]=1;
            restU[i]=.5+dx/(2*radius);restV[i]=.5+dy/(2*radius);
        }
        for(int stride=1;stride<=2;stride++)for(int i=0;i<count;i++) {
            int j=(i+stride)%count;
            edgeRest[(stride-1)*count+i]=length(x[i]-x[j],y[i]-y[j]);
        }
        restArea=area();
        double centerX=centerX(),centerY=centerY();
        for(int i=0;i<count;i++){int j=(i+1)%count;restFanCross[i]=cross(centerX,centerY,x[i],y[i],x[j],y[j]);}
        if(!validFan())throw new IllegalArgumentException("Invalid skin fan");
    }

    double centerX(){double c=0;for(double v:x)c+=v;return c/count;}
    double centerY(){double c=0;for(double v:y)c+=v;return c/count;}
    double principalDeformation(){
        double cx=centerX(),cy=centerY(),ru=0,rv=0;
        for(int i=0;i<count;i++){ru+=restU[i];rv+=restV[i];}ru/=count;rv/=count;
        double xx=0,xy=0,yy=0,px=0,py=0,qx=0,qy=0;
        for(int i=0;i<count;i++){double u=(restU[i]-ru)*2*radius,v=(restV[i]-rv)*2*radius,a=x[i]-cx,b=y[i]-cy;xx+=u*u;xy+=u*v;yy+=v*v;px+=a*u;py+=a*v;qx+=b*u;qy+=b*v;}
        double det=xx*yy-xy*xy;if(det<1e-12)return 0;
        double a=(px*yy-py*xy)/det,b=(py*xx-px*xy)/det,c=(qx*yy-qy*xy)/det,d=(qy*xx-qx*xy)/det;
        double trace=a*a+b*b+c*c+d*d,det2=(a*d-b*c)*(a*d-b*c),root=Math.sqrt(Math.max(0,trace*trace-4*det2));
        double min=Math.sqrt(Math.max(0,(trace-root)*.5)),max=Math.sqrt(Math.max(0,(trace+root)*.5));
        return Math.max(1-min,max-1);
    }
    void addVelocity(double dx,double dy,double angular) {
        double cx=centerX(),cy=centerY();
        for(int i=0;i<count;i++)if(inverseMass[i]>0){vx[i]+=dx-angular*(y[i]-cy);vy[i]+=dy+angular*(x[i]-cx);}
    }
    void releaseVelocity(double targetX,double targetY) {
        double meanX=0,meanY=0;for(int i=0;i<count;i++){meanX+=vx[i];meanY+=vy[i];}
        addVelocity(targetX-meanX/count,targetY-meanY/count,0);
    }
    double area(){double a=0;for(int i=0;i<count;i++){int j=(i+1)%count;a+=x[i]*y[j]-y[i]*x[j];}return a*.5;}

    void predict(double h,double gx,double gy,double maxSpeed) {
        if(!Double.isFinite(h+gx+gy+maxSpeed)||h<=0||h>1d/60||maxSpeed<0)
            throw new IllegalArgumentException("Invalid substep");
        if(tuningInitialized){softness+=(targetSoftness-softness)*(1-Math.exp(-h/.12));if(Math.abs(targetSoftness-softness)<1e-6)softness=targetSoftness;complianceScale=1/stiffness(softness);}
        areaLambda=0;java.util.Arrays.fill(edgeLambda,0);
        double limit=Math.min(maxSpeed,radius*.15/h);
        for(int i=0;i<count;i++) {
            previousX[i]=x[i];previousY[i]=y[i];
            if(inverseMass[i]==0){vx[i]=vy[i]=0;continue;}
            vx[i]+=gx*h;vy[i]+=gy*h;double speed=length(vx[i],vy[i]);
            if(!Double.isFinite(speed)){vx[i]=vy[i]=0;limitedVelocities++;}
            else if(speed>limit){vx[i]*=limit/speed;vy[i]*=limit/speed;limitedVelocities++;}
            x[i]+=vx[i]*h;y[i]+=vy[i]*h;
        }
    }

    void standUpright(double dt,double target){ standUpright(dt,target,350); }
    void standUpright(double dt,double target,double gravity){
        double cx=centerX(),cy=centerY(),dot=0,cross=0,moment=0,spin=0;
        for(int i=0;i<count;i++){double rx=restU[i]-.5,ry=restV[i]-.5,px=x[i]-cx,py=y[i]-cy;dot+=rx*px+ry*py;cross+=rx*py-ry*px;moment+=px*px+py*py;spin+=px*vy[i]-py*vx[i];}
        double angle=Math.atan2(cross,dot),error=Math.atan2(Math.sin(target-angle),Math.cos(target-angle));
        double omega=spin/Math.max(1,moment),change=Math.max(-12,Math.min(12,(error*(60+Math.abs(gravity)/Math.max(12,radius)*4)-omega*16)*Math.min(dt,.033)));
        addVelocity(0,0,change);
    }
    private void solveStone(double blend){
        double cx=centerX(),cy=centerY(),ux=0,uy=0,dot=0,cross=0;
        for(int i=0;i<count;i++){ux+=restU[i];uy+=restV[i];}ux/=count;uy/=count;
        for(int i=0;i<count;i++){double rx=restU[i]-ux,ry=restV[i]-uy;dot+=rx*(x[i]-cx)+ry*(y[i]-cy);cross+=rx*(y[i]-cy)-ry*(x[i]-cx);}
        double angle=Math.atan2(cross,dot),cs=Math.cos(angle),sn=Math.sin(angle);
        for(int i=0;i<count;i++)if(inverseMass[i]==0){double rx=(restU[i]-ux)*2*radius,ry=(restV[i]-uy)*2*radius;cx=x[i]-cs*rx+sn*ry;cy=y[i]-sn*rx-cs*ry;break;}
        for(int i=0;i<count;i++)if(inverseMass[i]>0){
            double rx=(restU[i]-ux)*2*radius,ry=(restV[i]-uy)*2*radius,dx=(cx+cs*rx-sn*ry-x[i])*blend,dy=(cy+sn*rx+cs*ry-y[i])*blend;
            double length=length(dx,dy),budget=Math.max(0,radius*.14-length(x[i]-previousX[i],y[i]-previousY[i]));
            double scale=rigid?1:(length>1e-9?Math.min(1,Math.min(radius*.025,budget)/length):1);x[i]+=dx*scale;y[i]+=dy*scale;
        }
    }
    void solveShape(double h) {
        if(rigid){solveStone(1);return;}
        for(int stride=1;stride<=2;stride++)for(int i=0;i<count;i++) {
            int j=(i+stride)%count,k=(stride-1)*count+i;
            double dx=x[i]-x[j],dy=y[i]-y[j],length=length(dx,dy);
            if(length<1e-9)continue;
            double alpha=(stride==1?8e-5:8e-4)*complianceScale/(h*h);
            double dl=(-(length-edgeRest[k])-alpha*edgeLambda[k])/(inverseMass[i]+inverseMass[j]+alpha);
            edgeLambda[k]+=dl;
            x[i]+=inverseMass[i]*dl*dx/length;y[i]+=inverseMass[i]*dl*dy/length;
            x[j]-=inverseMass[j]*dl*dx/length;y[j]-=inverseMass[j]*dl*dy/length;
        }
        double den=0;
        for(int i=0;i<count;i++) {
            int prev=(i+count-1)%count,next=(i+1)%count;
            areaGX[i]=(y[next]-y[prev])*.5;areaGY[i]=(x[prev]-x[next])*.5;
            den+=inverseMass[i]*(areaGX[i]*areaGX[i]+areaGY[i]*areaGY[i]);
        }
        if(den<1e-9)return;
        double alpha=4e-5/(h*h),dl=(-(area()-restArea)-alpha*areaLambda)/(den+alpha);
        areaLambda+=dl;
        for(int i=0;i<count;i++){x[i]+=inverseMass[i]*dl*areaGX[i];y[i]+=inverseMass[i]*dl*areaGY[i];}
        if(tuningInitialized&&softness<.2)solveStone(Math.pow(1-softness/.2,2));
        solveFan();
    }

    /** Unilateral triangle barrier, including the derivative of the moving centroid.
     * Prevents a concentrated drag force from folding a triangle and trapping the
     * entire world in repeated safety rollbacks. No artificial fixed centre. */
    void solveFan() {
        if(rigid){solveStone(1);return;}
        double cx=centerX(),cy=centerY();
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;
            double value=cross(cx,cy,x[i],y[i],x[j],y[j]),minimum=restFanCross[i]*.35;
            if(value>=minimum)continue;
            double gx=(y[i]-y[j])/count,gy=(x[j]-x[i])/count,den=0;
            for(int n=0;n<count;n++) {
                double dx=gx,dy=gy;
                if(n==i){dx+=y[j]-cy;dy+=cx-x[j];}
                if(n==j){dx+=cy-y[i];dy+=x[i]-cx;}
                areaGX[n]=dx;areaGY[n]=dy;den+=inverseMass[n]*(dx*dx+dy*dy);
            }
            if(den<1e-9)continue;
            double dl=(minimum-value)/den;
            for(int n=0;n<count;n++){x[n]+=inverseMass[n]*areaGX[n]*dl;y[n]+=inverseMass[n]*areaGY[n]*dl;}
            cx=centerX();cy=centerY();
        }
    }

    /** Inward unit normal, plane n dot p >= offset. Fixed nodes remain fixed. */
    void solvePlane(double nx,double ny,double offset) {
        double norm=length(nx,ny);
        if(!Double.isFinite(norm+offset)||norm<1e-9)throw new IllegalArgumentException("Invalid plane");
        nx/=norm;ny/=norm;offset/=norm;
        if(rigid){
            // A wall moves the whole object; clipping individual vertices would bend it.
            double correction=0;
            for(int i=0;i<count;i++)correction=Math.max(correction,offset-x[i]*nx-y[i]*ny);
            for(int i=0;i<count;i++){x[i]+=nx*correction;y[i]+=ny*correction;}
            return;
        }
        for(int i=0;i<count;i++)if(inverseMass[i]>0) {
            double gap=x[i]*nx+y[i]*ny-offset;
            if(gap<0){x[i]-=nx*gap;y[i]-=ny*gap;}
        }
    }

    void finish(double h) {
        double damping=Math.exp(-6*h),air=Math.pow(.84,h),meanX=0,meanY=0;
        boolean pinned=false;
        for(int i=0;i<count;i++) {
            pinned|=inverseMass[i]==0;
            vx[i]=inverseMass[i]==0?0:(x[i]-previousX[i])/h;
            vy[i]=inverseMass[i]==0?0:(y[i]-previousY[i])/h;
            meanX+=vx[i];meanY+=vy[i];
        }
        // Shape oscillation damping must not turn free fall into viscous terminal motion.
        meanX=pinned?0:meanX/count;meanY=pinned?0:meanY/count;
        for(int i=0;i<count;i++)if(inverseMass[i]>0){
            vx[i]=meanX*air+(vx[i]-meanX)*damping;
            vy[i]=meanY*air+(vy[i]-meanY)*damping;
        }
    }

    boolean positiveFan() {
        if(!Double.isFinite(area())||area()<restArea*.2)return false;
        double cx=centerX(),cy=centerY();
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;
            if(!Double.isFinite(x[i]+y[i])||(x[i]-cx)*(y[j]-cy)-(y[i]-cy)*(x[j]-cx)<=1e-7)return false;
        }
        return true;
    }
    boolean validFan() {
        if(!positiveFan())return false;
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;
            for(int k=i+2;k<count;k++) {
                if(i==0&&k==count-1)continue;int l=(k+1)%count;
                if(cross(x[i],y[i],x[j],y[j],x[k],y[k])*cross(x[i],y[i],x[j],y[j],x[l],y[l])<0
                        &&cross(x[k],y[k],x[l],y[l],x[i],y[i])*cross(x[k],y[k],x[l],y[l],x[j],y[j])<0)return false;
            }
        }
        return true;
    }

    /** Returns the triangle, or -1 outside. Output is caller-owned, no frame allocations. */
    int hitUV(double px,double py,double[] out) {
        double cx=centerX(),cy=centerY();
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;
            double den=cross(cx,cy,x[i],y[i],x[j],y[j]);if(den<=1e-7)continue;
            double a=cross(px,py,x[i],y[i],x[j],y[j])/den;
            double b=cross(cx,cy,px,py,x[j],y[j])/den,c=1-a-b;
            if(Math.min(a,Math.min(b,c))>=-1e-8) {
                out[0]=a*restCenterU+b*restU[i]+c*restU[j];out[1]=a*restCenterV+b*restV[i]+c*restV[j];return i;
            }
        }
        return -1;
    }
    boolean mapUV(double u,double v,double[] out) {
        double cx=centerX(),cy=centerY();
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;
            double den=cross(restCenterU,restCenterV,restU[i],restV[i],restU[j],restV[j]);
            double a=cross(u,v,restU[i],restV[i],restU[j],restV[j])/den;
            double b=cross(restCenterU,restCenterV,u,v,restU[j],restV[j])/den,c=1-a-b;
            if(Math.min(a,Math.min(b,c))>=-1e-8){out[0]=a*cx+b*x[i]+c*x[j];out[1]=a*cy+b*y[i]+c*y[j];return true;}
        }
        return false;
    }
    /** Same sector extrapolation used by BalloonMesh's full-texture UV fan. */
    boolean mapUVExtended(double u,double v,double[] out) {
        double cx=centerX(),cy=centerY();
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;
            double ax=restU[i]-restCenterU,ay=restV[i]-restCenterV;
            double bx=restU[j]-restCenterU,by=restV[j]-restCenterV;
            double den=ax*by-ay*bx,px=u-restCenterU,py=v-restCenterV;
            if(den<=1e-12)continue;
            double b=(px*by-py*bx)/den,c=(ax*py-ay*px)/den,a=1-b-c;
            if(b>=-1e-8&&c>=-1e-8){out[0]=a*cx+b*x[i]+c*x[j];out[1]=a*cy+b*y[i]+c*y[j];return true;}
        }
        out[0]=cx;out[1]=cy;return false;
    }
    private static double cross(double ax,double ay,double bx,double by,double cx,double cy){return(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);}
    private static double length(double x,double y){return Math.sqrt(x*x+y*y);}
}
