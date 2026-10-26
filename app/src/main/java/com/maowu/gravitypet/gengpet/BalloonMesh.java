package com.maowu.gravitypet.gengpet;

/** Shared triangle topology for native drawing and BalloonBody.hitUV. */
final class BalloonMesh {
    final float[] positions,texture;
    private final int count;
    private int[] sectors;
    private double[] weightB,weightC;
    BalloonMesh(BalloonBody body,int textureWidth,int textureHeight) {
        count=body.count;positions=new float[count*6];texture=new float[count*6];
        for(int i=0;i<count;i++) {
            int k=i*6,j=(i+1)%count;
            texture[k]=(float)(textureWidth*body.restCenterU);texture[k+1]=(float)(textureHeight*body.restCenterV);
            texture[k+2]=(float)(body.restU[i]*textureWidth);texture[k+3]=(float)(body.restV[i]*textureHeight);
            texture[k+4]=(float)(body.restU[j]*textureWidth);texture[k+5]=(float)(body.restV[j]*textureHeight);
        }
        update(body);
    }
    /** Extend the visual fan, retaining exactly the original physical nodes and UV map. */
    BalloonMesh(BalloonBody body,int textureWidth,int textureHeight,double left,double top,double right,double bottom) {
        count=body.count;
        java.util.ArrayList<double[]> vertices=new java.util.ArrayList<>();
        for(int i=0;i<count;i++) {
            int j=(i+1)%count;double ax=body.restU[i]-body.restCenterU,ay=body.restV[i]-body.restCenterV,bx=body.restU[j]-body.restCenterU,by=body.restV[j]-body.restCenterV;
            java.util.List<double[]> polygon=new java.util.ArrayList<>(java.util.Arrays.asList(new double[]{left,top},new double[]{right,top},new double[]{right,bottom},new double[]{left,bottom}));
            polygon=clip(polygon,body.restCenterU,body.restCenterV,ax,ay,1);
            polygon=clip(polygon,body.restCenterU,body.restCenterV,bx,by,-1);
            for(int k=1;k+1<polygon.size();k++)for(double[] p:new double[][]{polygon.get(0),polygon.get(k),polygon.get(k+1)})vertices.add(new double[]{p[0],p[1],i});
        }
        positions=new float[vertices.size()*2];texture=new float[positions.length];sectors=new int[vertices.size()];weightB=new double[vertices.size()];weightC=new double[vertices.size()];
        for(int k=0;k<vertices.size();k++){
            double[] p=vertices.get(k);int i=(int)p[2],j=(i+1)%count;sectors[k]=i;
            double ax=body.restU[i]-body.restCenterU,ay=body.restV[i]-body.restCenterV,bx=body.restU[j]-body.restCenterU,by=body.restV[j]-body.restCenterV,den=ax*by-ay*bx,px=p[0]-body.restCenterU,py=p[1]-body.restCenterV;
            weightB[k]=(px*by-py*bx)/den;weightC[k]=(ax*py-ay*px)/den;
            texture[k*2]=(float)((p[0]-left)/(right-left)*textureWidth);texture[k*2+1]=(float)((p[1]-top)/(bottom-top)*textureHeight);
        }
        update(body);
    }
    private static java.util.List<double[]> clip(java.util.List<double[]> input,double cx,double cy,double dx,double dy,int sign){
        java.util.List<double[]> out=new java.util.ArrayList<>();if(input.isEmpty())return out;
        double[] previous=input.get(input.size()-1);double a=sign*(dx*(previous[1]-cy)-dy*(previous[0]-cx));
        for(double[] point:input){double b=sign*(dx*(point[1]-cy)-dy*(point[0]-cx));
            if((a>=0)!=(b>=0)){double t=a/(a-b);out.add(new double[]{previous[0]+t*(point[0]-previous[0]),previous[1]+t*(point[1]-previous[1])});}
            if(b>=0)out.add(point);previous=point;a=b;
        }return out;
    }
    void update(BalloonBody body) {
        if(body.count!=count)throw new IllegalArgumentException("Mesh topology changed");
        float cx=(float)body.centerX(),cy=(float)body.centerY();
        if(sectors!=null){for(int k=0;k<sectors.length;k++){int i=sectors[k],j=(i+1)%count;double b=weightB[k],c=weightC[k],a=1-b-c;positions[k*2]=(float)(a*cx+b*body.x[i]+c*body.x[j]);positions[k*2+1]=(float)(a*cy+b*body.y[i]+c*body.y[j]);}return;}
        for(int i=0;i<count;i++) {
            int k=i*6,j=(i+1)%count;
            positions[k]=cx;positions[k+1]=cy;positions[k+2]=(float)body.x[i];positions[k+3]=(float)body.y[i];
            positions[k+4]=(float)body.x[j];positions[k+5]=(float)body.y[j];
        }
    }
}
