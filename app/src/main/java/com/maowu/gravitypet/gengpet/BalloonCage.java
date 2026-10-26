package com.maowu.gravitypet.gengpet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** boxob's immutable convex UV cage, adapted to metadata instead of SkinCollisionGeometry. */
final class BalloonCage {
  final double[] u, v;
  final double centerU, centerV;

  private BalloonCage(double[] u, double[] v) {
    this.u = u; this.v = v;
    double x = 0, y = 0;
    for (int i = 0; i < u.length; i++) { x += u[i]; y += v[i]; }
    centerU = x / u.length; centerV = y / v.length;
  }

  static BalloonCage circle(int count) {
    if (count < 8 || count > 32) throw new IllegalArgumentException("Invalid cage count");
    double[] u = new double[count], v = new double[count];
    for (int i = 0; i < count; i++) {
      double angle = 2 * Math.PI * i / count;
      u[i] = .5 + .5 * Math.cos(angle); v[i] = .5 + .5 * Math.sin(angle);
    }
    return new BalloonCage(u, v);
  }

  /** Pairs u0,v0,u1,v1,... in the ORIGINAL bitmap's normalized canvas coordinates.
   * Builds the convex skin hull (same physical approximation as boxob), then retains
   * its corners while subdividing longest edges to exactly 24 nodes. */
  static BalloonCage fromUV(float[] contour, int count) {
    if (contour == null || contour.length < 6 || (contour.length & 1) != 0
        || contour.length > 4096 || count < 8 || count > 32)
      throw new IllegalArgumentException("Expected normalized UV pairs");
    double[][] points = new double[contour.length / 2][2];
    for (int i = 0; i < points.length; i++) {
      double x = contour[i * 2], y = contour[i * 2 + 1];
      if (!Double.isFinite(x + y) || x < 0 || x > 1 || y < 0 || y > 1)
        throw new IllegalArgumentException("UV outside bitmap");
      points[i][0] = x; points[i][1] = y;
    }
    if(points.length==count){
      boolean convex=true;double area=0;
      for(int i=0;i<count;i++){
        double[] a=points[i],b=points[(i+1)%count],c=points[(i+2)%count];
        if(cross(a,b,c)<-1e-9||Math.hypot(a[0]-b[0],a[1]-b[1])<1e-8)convex=false;
        area+=a[0]*b[1]-a[1]*b[0];
      }
      if(convex&&area>1e-7){
        double[] u=new double[count],v=new double[count];
        for(int i=0;i<count;i++){u[i]=points[i][0];v[i]=points[i][1];}
        return new BalloonCage(u,v);
      }
    }
    Arrays.sort(points, Comparator.<double[]>comparingDouble(p -> p[0]).thenComparingDouble(p -> p[1]));
    ArrayList<double[]> hull = new ArrayList<>();
    for (double[] p : points) {
      while (hull.size() >= 2 && cross(hull.get(hull.size()-2), hull.get(hull.size()-1), p) <= 1e-10) hull.remove(hull.size()-1);
      hull.add(p);
    }
    int lower = hull.size();
    for (int i = points.length - 2; i >= 0; i--) {
      double[] p = points[i];
      while (hull.size() > lower && cross(hull.get(hull.size()-2), hull.get(hull.size()-1), p) <= 1e-10) hull.remove(hull.size()-1);
      hull.add(p);
    }
    if (!hull.isEmpty()) hull.remove(hull.size()-1);
    if (hull.size() < 3) throw new IllegalArgumentException("Degenerate contour");
    // Metadata with more than 24 hull corners is reduced by least area loss. The renderer
    // extends its UV fan to the full bitmap, so this never clips the visible artwork.
    while (hull.size() > count) {
      int remove = 0; double least = Double.POSITIVE_INFINITY;
      for (int i = 0; i < hull.size(); i++) {
        double area = cross(hull.get((i + hull.size()-1) % hull.size()), hull.get(i), hull.get((i+1) % hull.size()));
        if (area < least) { least = area; remove = i; }
      }
      hull.remove(remove);
    }
    while (hull.size() < count) {
      int edge = 0; double longest = -1;
      for (int i = 0; i < hull.size(); i++) {
        double[] a = hull.get(i), b = hull.get((i+1) % hull.size());
        double d = Math.pow(a[0]-b[0], 2) + Math.pow(a[1]-b[1], 2);
        if (d > longest) { longest = d; edge = i; }
      }
      double[] a = hull.get(edge), b = hull.get((edge+1) % hull.size());
      hull.add(edge+1, new double[]{(a[0]+b[0])*.5, (a[1]+b[1])*.5});
    }
    double[] u = new double[count], v = new double[count]; double area = 0;
    for (int i = 0; i < count; i++) { u[i] = hull.get(i)[0]; v[i] = hull.get(i)[1]; }
    for (int i = 0; i < count; i++) area += u[i]*v[(i+1)%count] - v[i]*u[(i+1)%count];
    if (area < 1e-7) throw new IllegalArgumentException("Degenerate skin area");
    return new BalloonCage(u, v);
  }
  private static double cross(double[] a, double[] b, double[] c) {
    return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]);
  }
}
