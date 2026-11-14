package com.fourtwo.hookintent.widgetlab;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.RemoteViews;

/** Real continuously transformed cards. Kept outside the release app until host support is proven. */
@RemoteViews.RemoteView
public final class RotaryCardsView extends View {
    private final RotaryCardRenderer renderer;
    private final int slop;
    private float position, downY, downX, initialPosition;
    private boolean dragging;
    private ValueAnimator animator;

    public RotaryCardsView(Context context) { this(context,null); }
    public RotaryCardsView(Context context,AttributeSet attrs) {
        super(context,attrs);renderer=new RotaryCardRenderer(context);
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        setClickable(true);setFocusable(true);describeCurrent();
    }
    float position() { return position; }
    void restorePosition(float value) { position=value;describeCurrent();invalidate(); }
    private int current() { return Math.floorMod(Math.round(position),RotaryCardRenderer.NAMES.length); }
    private void describeCurrent() { setContentDescription(RotaryCardRenderer.NAMES[current()]+"，上下滑动切换"); }
    private float spacing() { return Math.max(48*getResources().getDisplayMetrics().density,getHeight()*.28f); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        renderer.draw(canvas,getWidth(),getHeight(),position);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if(animator != null) animator.cancel();
                downY=event.getY();downX=event.getX();initialPosition=position;dragging=false;return true;
            case MotionEvent.ACTION_MOVE:
                if(Math.abs(event.getY()-downY)>slop) {
                    dragging=true;getParent().requestDisallowInterceptTouchEvent(true);
                    position=initialPosition-(event.getY()-downY)/spacing();invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                float width=renderer.cardWidth(getWidth());
                float height=renderer.cardHeight(getWidth(),getHeight());
                if(!dragging && Math.abs(event.getX()-downX)<slop
                        && Math.abs(event.getY()-getHeight()/2f)<height/2
                        && Math.abs(event.getX()-getWidth()/2f)<width/2) performClick();
                snap();return true;
            case MotionEvent.ACTION_CANCEL: snap();return true;
            default:return true;
        }
    }
    private void snap() {
        animator=ValueAnimator.ofFloat(position,Math.round(position));
        animator.setDuration(220);animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {position=(float)a.getAnimatedValue();describeCurrent();invalidate();});
        animator.start();
    }
    @Override public boolean performClick() {
        super.performClick();getContext().startActivity(new Intent(RotaryCardRenderer.ACTIONS[current()]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;
    }
    @Override protected void onDetachedFromWindow() { if(animator != null) animator.cancel();super.onDetachedFromWindow(); }
}
