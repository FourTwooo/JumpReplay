package com.fourtwo.hookintent.widgetlab;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import java.util.*;

final class RotaryCardRenderer {
    static final String[] NAMES={"Wi-Fi 设置","蓝牙设置","系统设置"};
    static final String[] ACTIONS={Settings.ACTION_WIFI_SETTINGS,Settings.ACTION_BLUETOOTH_SETTINGS,Settings.ACTION_SETTINGS};
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Camera camera=new Camera();
    private final Matrix matrix=new Matrix();
    private final Drawable icon;
    private final float density;
    private final boolean night;
    RotaryCardRenderer(Context context) {
        density=context.getResources().getDisplayMetrics().density;
        camera.setLocation(0,0,-8*density);
        icon=context.getDrawable(android.R.drawable.ic_menu_manage);
        night=(context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }
    float cardWidth(int width) { return Math.min(width-8*density,300*density); }
    float cardHeight(int width,int height) {
        return Math.min(cardWidth(width)*(width<180*density ? .85f : .5f),height*.48f);
    }
    void draw(Canvas canvas,int width,int height,float position) {
        float cardWidth=cardWidth(width),cardHeight=cardHeight(width,height);
        float radius=Math.min(height*.36f,cardHeight*.9f);
        List<float[]> visible=new ArrayList<>();
        for(int i=0;i<NAMES.length;i++) {
            float offset=i-position;offset-=Math.round(offset/NAMES.length)*NAMES.length;
            visible.add(new float[]{i,offset});
        }
        visible.sort((a,b) -> Float.compare(Math.abs(b[1]),Math.abs(a[1])));
        for(float[] entry:visible) {
            float angle=entry[1]*58,radians=(float)Math.toRadians(angle);
            float scale=.83f+.17f*(float)Math.cos(radians),y=(float)Math.sin(radians)*radius;
            camera.save();camera.rotateX(-angle);camera.getMatrix(matrix);camera.restore();
            matrix.preTranslate(-cardWidth/2,-cardHeight/2);matrix.postScale(scale,scale);
            matrix.postTranslate(width/2f,height/2f+y);
            canvas.save();canvas.concat(matrix);
            RectF rect=new RectF(0,0,cardWidth,cardHeight);
            paint.setStyle(Paint.Style.FILL);paint.setColor(night ? 0xff29282e : 0xfffdfcfe);
            canvas.drawRoundRect(rect,10*density,10*density,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);
            paint.setColor(night ? 0xff55545d : 0xffd9d7df);canvas.drawRoundRect(rect,10*density,10*density,paint);
            paint.setStyle(Paint.Style.FILL);
            boolean iconOnly=width<100*density;
            int iconSize=(int)Math.min(32*density,cardHeight*(iconOnly ? .6f : .35f));
            int iconX=(int)(iconOnly ? (cardWidth-iconSize)/2 : 12*density),iconY=(int)((cardHeight-iconSize)/2);
            icon.setBounds(iconX,iconY,iconX+iconSize,iconY+iconSize);icon.draw(canvas);
            if(!iconOnly) {
                paint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
                paint.setTextSize((width<180*density ? 13 : 16)*density);paint.setColor(night ? 0xffeeecf3 : 0xff34333b);
                float textX=iconX+iconSize+8*density;
                String title=NAMES[(int)entry[0]];
                int length=paint.breakText(title,true,Math.max(0,cardWidth-textX-12*density),null);
                if(length<title.length()) title=title.substring(0,Math.max(0,length-1))+"…";
                canvas.drawText(title,textX,(cardHeight-paint.ascent()-paint.descent())/2,paint);
            }
            canvas.restore();
        }
    }
}
