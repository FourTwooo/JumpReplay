package com.fourtwo.hookintent.widgetlab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class LabLaunchActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int index=getIntent().getIntExtra("lab_index",-1);
        if(index>=0 && index<RotaryCardRenderer.ACTIONS.length)
            startActivity(new Intent(RotaryCardRenderer.ACTIONS[index]));
        finish();
    }
}
