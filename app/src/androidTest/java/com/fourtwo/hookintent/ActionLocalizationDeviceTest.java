package com.fourtwo.hookintent;

import android.app.Instrumentation;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fourtwo.hookintent.actions.ActionEditorActivity;
import com.fourtwo.hookintent.widget.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ActionLocalizationDeviceTest {
    @Test public void actionControlsAndWidgetInstructionsUseSelectedLanguage() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        // On API 33 AppCompat needs an attached delegate to locate LocaleManager;
        // instrumentation starts without an Activity, so use the same platform service directly.
        java.io.File localeFile=new java.io.File(context.getFilesDir(),"androidx.appcompat.app.AppCompatDelegate.application_locales_record_file");
        byte[] previousRecord=localeFile.exists() ? java.nio.file.Files.readAllBytes(localeFile.toPath()) : null;
        LocaleListCompat previous=android.os.Build.VERSION.SDK_INT>=33
                ? LocaleListCompat.wrap(context.getSystemService(android.app.LocaleManager.class).getApplicationLocales())
                : LocaleListCompat.forLanguageTags(storedLocales(context));
        try {
            for(String language:new String[]{"en","ja"}) {
                instrumentation.runOnMainSync(() -> setLocales(context,LocaleListCompat.forLanguageTags(language)));
                instrumentation.waitForIdleSync();
                try(ActivityScenario<ActionEditorActivity> scenario=ActivityScenario.launch(new Intent(context,ActionEditorActivity.class).putExtra("initial_uri","demo://VALUE"))) {
                    AtomicBoolean ready=new AtomicBoolean();
                    for(int i=0;i<100 && !ready.get();i++) {
                        scenario.onActivity(a -> ready.set(a.findViewById(R.id.submit_button).isEnabled()));
                        if(!ready.get()) Thread.sleep(50);
                    }
                    assertTrue(ready.get());
                    scenario.onActivity(a -> {
                        assertEquals(language.equals("en") ? "Choose from apps" : "アプリから選択",a.getResources().getStringArray(R.array.action_icon_options)[0]);
                        assertEquals(language.equals("en") ? "Delete action" : "アクションを削除",a.getResources().getStringArray(R.array.action_menu_options)[3]);
                        a.findViewById(R.id.add_param_button).performClick();
                        android.view.ViewGroup rows=a.findViewById(R.id.dynamic_params_container);
                        android.view.ViewGroup row=(android.view.ViewGroup)rows.getChildAt(rows.getChildCount()-1);
                        assertEquals(language.equals("en") ? "Original text to replace" : "置換する元の文字列",((EditText)row.getChildAt(0)).getHint().toString());
                        try {
                            com.fourtwo.hookintent.utils.ShortcutParameterReplacer.validate("demo://VALUE",new String[]{""});fail();
                        } catch(IllegalArgumentException error) {
                            assertEquals(language.equals("en") ? "Replacement text cannot be empty" : "置換する文字列を入力してください",
                                    com.fourtwo.hookintent.actions.ActionValidationException.text(a,error));
                        }
                        try {
                            com.fourtwo.hookintent.utils.ShortcutParameterReplacer.validate("demo://VALUE",new String[]{"VALUE","VALUE"});fail();
                        } catch(IllegalArgumentException error) {
                            assertEquals(language.equals("en") ? "Duplicate replacement text: VALUE" : "置換する文字列が重複しています：VALUE",
                                    com.fourtwo.hookintent.actions.ActionValidationException.text(a,error));
                        }
                        ((EditText)row.getChildAt(0)).setText("VALUE");
                        ((EditText)a.findViewById(R.id.shortcut_name)).setText("QA localized launch mode");
                        ((android.widget.CheckBox)a.findViewById(R.id.enable_feature_checkbox)).setChecked(true);
                        ((android.widget.Spinner)a.findViewById(R.id.dropdown_spinner)).setSelection(2);
                        try {
                            java.lang.reflect.Method read=ActionEditorActivity.class.getDeclaredMethod("readForm");read.setAccessible(true);
                            com.fourtwo.hookintent.actions.QuickAction value=(com.fourtwo.hookintent.actions.QuickAction)read.invoke(a);
                            value.validate();assertEquals("Shizuku - 系统助手",value.mode);
                        } catch(Exception error) { throw new AssertionError(error); }
                        assertEquals(language.equals("en") ? "Save cards" : "カードを保存",a.getString(R.string.action_widget_save));
                        assertTrue(a.getString(R.string.action_widget_instructions).contains(language.equals("en") ? "Each widget keeps its own selection." : "選択内容はウィジェットごとに保存されます。"));
                    });
                }
                int[] ids=AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context,ActionWidgetProvider.class));
                if(ids.length>0) {
                    java.util.List<String> before=ActionWidgetProvider.selection(context,ids[0]);
                    try(ActivityScenario<ActionWidgetConfigureActivity> scenario=ActivityScenario.launch(new Intent(context,ActionWidgetConfigureActivity.class)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,ids[0]))) {
                        scenario.onActivity(a -> assertEquals(language.equals("en") ? "Save cards" : "カードを保存",a.getString(R.string.action_widget_save)));
                    }
                    assertEquals(before,ActionWidgetProvider.selection(context,ids[0]));
                }
                if(android.os.Build.VERSION.SDK_INT<33) {
                    for(int i=0;i<100 && !language.equals(storedLocales(context));i++) Thread.sleep(50);
                    assertEquals(language,storedLocales(context));
                }
            }
        } finally {
            instrumentation.runOnMainSync(() -> setLocales(context,previous));
            instrumentation.waitForIdleSync();
            if(android.os.Build.VERSION.SDK_INT<33) {
                // No delegate remains after closing the scenarios, so AppCompat cannot
                // persist this final reset itself. Restore only the locale test fixture.
                if(previousRecord==null) { if(localeFile.exists()) assertTrue(localeFile.delete()); }
                else java.nio.file.Files.write(localeFile.toPath(),previousRecord);
                assertEquals(previous.toLanguageTags(),storedLocales(context));
            }
        }
    }
    private String storedLocales(Context context) throws Exception {
        try(java.io.InputStream input=context.openFileInput("androidx.appcompat.app.AppCompatDelegate.application_locales_record_file")) {
            org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(input,"UTF-8");
            int event;
            while((event=parser.next())!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)
                if(event==org.xmlpull.v1.XmlPullParser.START_TAG && "locales".equals(parser.getName()))
                    return parser.getAttributeValue(null,"application_locales");
            return "";
        } catch(java.io.FileNotFoundException missing) { return ""; }
    }
    private void setLocales(Context context,LocaleListCompat locales) {
        if(android.os.Build.VERSION.SDK_INT>=33) context.getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(android.os.LocaleList.forLanguageTags(locales.toLanguageTags()));
        else AppCompatDelegate.setApplicationLocales(locales);
    }
}
