package com.fourtwo.hookintent.widgetlab;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

public final class LabActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        float density=getResources().getDisplayMetrics().density;
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(20*density),(int)(24*density),(int)(20*density),(int)(24*density));
        root.setBackgroundColor(0xfff5f4f7);
        TextView title=new TextView(this); title.setText("轮转卡片 · 独立交互原型"); title.setTextSize(20);
        title.setTextColor(0xff303037);root.addView(title);
        TextView hint=new TextView(this);
        hint.setText("应用内为连续轮转；桌面测试组件采用系统翻页。\n小尺寸仅显示图标，放大后显示昵称。");
        hint.setTextSize(14);hint.setPadding(0,(int)(12*density),0,(int)(16*density));root.addView(hint);
        RotaryCardsView cards=new RotaryCardsView(this);
        if(state != null) cards.restorePosition(state.getFloat("position"));
        cards.setId(android.R.id.custom);
        LinearLayout sizes=new LinearLayout(this);root.addView(sizes);
        FrameLayout preview=new FrameLayout(this);
        root.addView(preview,new LinearLayout.LayoutParams(-1,(int)(380*density)));
        preview.addView(cards,new FrameLayout.LayoutParams((int)(138*density),(int)(243*density),Gravity.CENTER));
        for(int[] size:new int[][]{{1,1,61,113},{2,1,138,113},{2,2,138,243},{2,3,138,373}}) {
            Button button=new Button(this);button.setText(size[0]+"×"+size[1]);button.setTextSize(13);button.setMinWidth(0);
            sizes.addView(button,new LinearLayout.LayoutParams(0,-2,1));
            button.setOnClickListener(v -> cards.setLayoutParams(new FrameLayout.LayoutParams(
                    (int)(size[2]*density),(int)(size[3]*density),Gravity.CENTER)));
        }
        Button pin=new Button(this);pin.setText("申请添加测试组件");root.addView(pin);
        pin.setOnClickListener(v -> {
            AppWidgetManager manager=getSystemService(AppWidgetManager.class);
            boolean requested=android.os.Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported()
                    && manager.requestPinAppWidget(new ComponentName(this,LabWidget.class),null,null);
            if(!requested) Toast.makeText(this,"桌面不支持申请添加组件",Toast.LENGTH_LONG).show();
        });
        setContentView(root);
    }
    @Override public void onSaveInstanceState(Bundle state) {
        state.putFloat("position",((RotaryCardsView)findViewById(android.R.id.custom)).position());
        super.onSaveInstanceState(state);
    }
}
