package com.maowu.gravitypet.gengpet;

/** Continuous point versus moving segment test, without temporary allocations. */
final class BalloonSweep {
    static boolean crosses(double px,double py,double qx,double qy,
                           double ax,double ay,double cx,double cy,double bx,double by,double dx,double dy) {
        if(Math.max(px,qx)<Math.min(Math.min(ax,bx),Math.min(cx,dx))-1e-8
                ||Math.min(px,qx)>Math.max(Math.max(ax,bx),Math.max(cx,dx))+1e-8
                ||Math.max(py,qy)<Math.min(Math.min(ay,by),Math.min(cy,dy))-1e-8
                ||Math.min(py,qy)>Math.max(Math.max(ay,by),Math.max(cy,dy))+1e-8)return false;
        double ex=bx-ax,ey=by-ay,fx=dx-cx-ex,fy=dy-cy-ey;
        double rx=px-ax,ry=py-ay,ux=qx-cx-rx,uy=qy-cy-ry;
        double a=fx*uy-fy*ux,b=ex*uy-ey*ux+fx*ry-fy*rx,c=ex*ry-ey*rx;
        if(Math.abs(a)<1e-10) {
            if(Math.abs(b)>1e-10)return hit(-c/b,px,py,qx,qy,ax,ay,cx,cy,bx,by,dx,dy);
            // Collinear swept boxes are conservatively rejected by the restricted solver.
            return Math.abs(c)<1e-10;
        }
        double disc=b*b-4*a*c;if(disc< -1e-10)return false;
        double root=Math.sqrt(Math.max(0,disc)),z=-.5*(b+(b>=0?root:-root));
        if(Math.abs(z)<1e-10)return hit(-b/(2*a),px,py,qx,qy,ax,ay,cx,cy,bx,by,dx,dy);
        return hit(z/a,px,py,qx,qy,ax,ay,cx,cy,bx,by,dx,dy)
                ||hit(c/z,px,py,qx,qy,ax,ay,cx,cy,bx,by,dx,dy);
    }
    private static boolean hit(double t,double px,double py,double qx,double qy,
                               double ax,double ay,double cx,double cy,double bx,double by,double dx,double dy) {
        if(t<=1e-8||t>1+1e-8)return false;
        double x=px+(qx-px)*t,y=py+(qy-py)*t;
        double a=ax+(cx-ax)*t,b=ay+(cy-ay)*t,c=bx+(dx-bx)*t,d=by+(dy-by)*t;
        double ex=c-a,ey=d-b,len=ex*ex+ey*ey;if(len<1e-12)return false;
        double u=((x-a)*ex+(y-b)*ey)/len;return u>=-1e-8&&u<=1+1e-8;
    }
}
