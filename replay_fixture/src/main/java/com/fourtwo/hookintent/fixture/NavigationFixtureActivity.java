package com.fourtwo.hookintent.fixture;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

/** Drives an ordinary Jetpack action and an exported deep link for device replay checks. */
public final class NavigationFixtureActivity extends FragmentActivity {
    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_navigation_fixture);
        if (state == null) openDeepLink(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openDeepLink(intent);
    }

    private void openDeepLink(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || !"jumpreplay-fixture".equals(intent.getScheme())) return;
        String value = intent.getStringExtra("launchValue");
        if (value == null) return;
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.navigation_fixture_host);
        NavController controller = host.getNavController();
        controller.popBackStack(R.id.nav_fixture_home, false);
        openDetail(controller, value);
    }

    private static void openDetail(NavController controller, String value) {
        Bundle arguments = new Bundle();
        arguments.putString("value", value);
        controller.navigate(R.id.nav_fixture_open_detail, arguments);
    }

    public static final class Home extends Fragment {
        @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle state) {
            Button button = new Button(requireContext());
            button.setText("Open navigation detail");
            button.setOnClickListener(view -> openDetail(NavHostFragment.findNavController(this), "initial"));
            return button;
        }
    }

    public static final class Detail extends Fragment {
        @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle state) {
            TextView label = new TextView(requireContext());
            label.setGravity(Gravity.CENTER);
            label.setText("Navigation detail: " + requireArguments().getString("value"));
            return label;
        }
    }
}
