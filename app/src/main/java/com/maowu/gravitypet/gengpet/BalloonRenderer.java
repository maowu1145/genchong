package com.maowu.gravitypet.gengpet;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;

/** Draws the same triangle fan used by native collision nodes and UV hit testing. */
final class BalloonRenderer {
    final BalloonMesh mesh;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    BalloonRenderer(BalloonBody body,Bitmap bitmap) {
        mesh=new BalloonMesh(body,bitmap.getWidth(),bitmap.getHeight());
        paint.setShader(new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
    }
    BalloonRenderer(BalloonBody body,Bitmap bitmap,android.graphics.RectF bounds) {
        mesh=new BalloonMesh(body,bitmap.getWidth(),bitmap.getHeight(),bounds.left,bounds.top,bounds.right,bounds.bottom);
        paint.setShader(new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
    }
    void draw(Canvas canvas,BalloonBody body) {
        mesh.update(body);
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES,mesh.positions.length,mesh.positions,0,
                mesh.texture,0,null,0,null,0,0,paint);
    }
}
