package com.fourtwo.hookintent.xposed.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.fourtwo.hookintent.data.ImagesBase64;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;

@SuppressLint("ViewConstructor")
public class FloatWindow extends LinearLayout {

    // 保存悬浮窗的全局坐标
    public static int savedX = 200; // 默认 X 坐标
    public static int savedY = 100; // 默认 Y 坐标

    public boolean isOnPause = false;
    private int touchSlop; // 滑动距离阈值
    private final ViewGroup viewGroup;
    private final Context applicationContext;


    public void setIsOnPause(boolean _isOnPause){
        isOnPause = _isOnPause;
    }

    public FloatWindow(Context context, Activity activity) {
        super(context);
        applicationContext = context;
        viewGroup = (ViewGroup) activity.getWindow().getDecorView();
    }

    private boolean isViewAdded(ViewGroup viewGroup) {
        int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = viewGroup.getChildAt(i);
            // 判断是否是悬浮窗
            if (child instanceof FloatWindow || child.getClass().getName().equals(FloatWindow.class.getName())) {
                // 如果悬浮窗不是顶层视图，调整到顶层
                if (i != childCount - 1) {
                    child.bringToFront();
//                    viewGroup.removeView(child); // 移除悬浮窗
//                    viewGroup.addView(child, 1);   // 添加到顶层
                    XposedBridge.log("[悬浮窗层级调整完毕] " + i + " => " + childCount);
                }

                FrameLayout.LayoutParams childLayoutParams = (FrameLayout.LayoutParams) child.getLayoutParams();
                if (childLayoutParams != null) {
                    int currentX = childLayoutParams.leftMargin;
                    int currentY = childLayoutParams.topMargin;

                    // 判断位置是否超出误差范围，只有超出范围才更新位置
                    if (Math.abs(currentX - FloatWindow.savedX) > 5 || Math.abs(currentY - FloatWindow.savedY) > 5) {
                        XposedBridge.log("位置发生变化，更新悬浮窗位置");
                        updatePosition(); // 更新位置
                    }
                }
                return false; // 返回 false 表示悬浮窗已经存在
            }
        }
        return true;
    }

    public void initialize() {
        // 检查是否已经添加悬浮窗
        if (isViewAdded(viewGroup)) {
            createView(applicationContext, viewGroup);
        }

        // 监听视图变化，确保悬浮窗持续显示
        viewGroup.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (isViewAdded(viewGroup) && !isOnPause) {
                 createView(applicationContext, viewGroup);
            }
        });
    }

    public FloatWindowView floatWindowView;
    private boolean isTextViewVisible = false;            // 弹出 View 是否可见

    public void setFloatWindowView(FloatWindowView floatWindowView){
        this.floatWindowView = floatWindowView;
    }

    private void animateTextView(boolean hide) {
        if (floatWindowView == null){return;}

        float startAlpha = hide ? 1f : 0f;
        float endAlpha = hide ? 0f : 1f;
        int startTranslationX = hide ? 0 : 0;
        int endTranslationX = hide ? 0 : 0;

        ValueAnimator animator = ValueAnimator.ofFloat(startAlpha, endAlpha);
        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            floatWindowView.setAlpha(value);
            floatWindowView.setTranslationX(startTranslationX + (endTranslationX - startTranslationX) * value);
        });
        animator.setDuration(400);
        animator.start();

        floatWindowView.setVisibility(hide ? View.GONE : View.VISIBLE);
    }

    private void toggleTextView() {
        if (isTextViewVisible) {
            // 隐藏 TextView
            animateTextView(true);
            isTextViewVisible = false;
        } else {
            animateTextView(false);
            isTextViewVisible = true;
        }
    }

    private void updatePosition() {
        // 获取当前布局参数
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) FloatWindow.this.getLayoutParams();

        if (layoutParams != null) {        // 更新位置
            layoutParams.leftMargin = FloatWindow.savedX;
            layoutParams.topMargin = FloatWindow.savedY;

            // 应用新的位置
            FloatWindow.this.setLayoutParams(layoutParams);
        }
    }

    private ImageView imageView;

    public void removeView(){
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof FloatWindow) {
                viewGroup.removeView(child);
                XposedBridge.log("删除悬浮窗成功");
            }
        }
    }
    @SuppressLint({"ClickableViewAccessibility", "ResourceType", "RtlHardcoded", "UseCompatLoadingForDrawables"})
    private void createView(Context context, ViewGroup viewGroup) {
        this.touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop(); // 滑动距离阈值

        this.setOrientation(LinearLayout.HORIZONTAL);

        imageView = new ImageView(context);
        imageView.setAlpha(0.7f);

        imageView.setImageDrawable(ImagesBase64.base64ToDrawable(context, ImagesBase64.r_drawable_ic_launcher_round));
        imageView.setOnClickListener(v -> toggleTextView());
        imageView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private float lastX, lastY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        lastX = downX;
                        lastY = downY;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        // 当前手指位置
                        float moveX = event.getRawX();
                        float moveY = event.getRawY();

                        // 计算移动距离
                        float deltaX = moveX - lastX;
                        float deltaY = moveY - lastY;

                        // 保存当前坐标
                        lastX = moveX;
                        lastY = moveY;

                        // 更新悬浮窗位置
                        FloatWindow.savedX += (int) deltaX;
                        FloatWindow.savedY += (int) deltaY;
                        updatePosition();
                        break;
                    case MotionEvent.ACTION_UP:
//                        XposedBridge.log("点击");
                        if (Math.abs(event.getRawX() - downX) < touchSlop && Math.abs(event.getRawY() - downY) < touchSlop) {
                            try {
                                @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"}) Field field = View.class.getDeclaredField("mListenerInfo");
                                field.setAccessible(true);
                                Object object = field.get(imageView);
                                assert object != null;
                                field = object.getClass().getDeclaredField("mOnClickListener");
                                field.setAccessible(true);
                                object = field.get(object);
                                if (object instanceof OnClickListener) {
                                    ((View.OnClickListener) object).onClick(imageView);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        break;
                }
                return true;
            }
        });

        this.addView(imageView, 0);
        if (floatWindowView != null) {
            if (floatWindowView.getParent() != null) {
                ViewGroup parent = (ViewGroup) floatWindowView.getParent();
                parent.removeView(floatWindowView);
                XposedBridge.log("从父视图中移除了 floatWindowView");
            }

            this.addView(floatWindowView);
        }

        // 设置布局参数
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );

        // 使用保存的坐标
        layoutParams.topMargin = FloatWindow.savedY; // y 坐标
        layoutParams.leftMargin = FloatWindow.savedX; // x 坐标


        viewGroup.addView(this, layoutParams);

        isViewAdded(viewGroup);
        XposedBridge.log("悬浮窗加载完毕");

        setClickable(true); // 设置当前 View 可点击
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent motionEvent) {
        return super.dispatchTouchEvent(motionEvent);
    }

}
