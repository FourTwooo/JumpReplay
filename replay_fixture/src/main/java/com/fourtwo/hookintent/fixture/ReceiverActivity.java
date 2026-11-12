package com.fourtwo.hookintent.fixture;

import android.app.Activity;
import android.os.Bundle;
import org.json.JSONObject;

public final class ReceiverActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        JSONObject json = new JSONObject();
        try {
            Bundle receipt = FixtureService.inspect(getIntent());
            for (String key : receipt.keySet()) json.put(key, receipt.get(key));
        } catch (Throwable error) {
            try { json.put("error", error.toString()); } catch (Exception ignored) { }
        }
        getSharedPreferences("receipt", 0).edit().putString("json", json.toString()).commit();
        finish();
    }
}
