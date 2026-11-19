package com.fourtwo.hookintent.fixture;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import java.io.Serializable;

/** Drives Jetpack and direct Fragment navigation for device replay checks. */
public final class NavigationFixtureActivity extends FragmentActivity {
    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_navigation_fixture);
        if (getSupportFragmentManager().findFragmentById(R.id.typed_panel_container) == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.typed_panel_container, new TypedPanel())
                    .commitNow();
        }
        if (state != null && state.getBoolean("typed_panel_visible")) setPanelVisibility(View.VISIBLE);
        if (state == null) openDeepLink(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openDeepLink(intent);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("typed_panel_visible", panelVisible());
    }

    @Override public void onBackPressed() {
        if (panelVisible()) setPanelVisibility(View.GONE);
        else super.onBackPressed();
    }

    private boolean panelVisible() {
        return findViewById(R.id.typed_panel_container).getVisibility() == View.VISIBLE;
    }

    private void setPanelVisibility(int visibility) {
        findViewById(R.id.typed_panel_container).setVisibility(visibility);
        findViewById(R.id.navigation_fixture_host).setVisibility(visibility == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** The Fragment already exists. Opening it only changes its arguments and the containers' visibility. */
    public int showTypedPanel(int count, Bundle options, boolean enabled, String title) {
        TypedPanel panel = (TypedPanel) getSupportFragmentManager().findFragmentById(R.id.typed_panel_container);
        Bundle arguments = new Bundle(options);
        arguments.putInt("count", count);
        arguments.putBoolean("enabled", enabled);
        arguments.putString("title", title);
        panel.setArguments(arguments);
        panel.render();
        setPanelVisibility(View.VISIBLE);
        return count;
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
            LinearLayout buttons = new LinearLayout(requireContext());
            buttons.setOrientation(LinearLayout.VERTICAL);
            Button navigation = new Button(requireContext());
            navigation.setText("Open navigation detail");
            navigation.setOnClickListener(view -> openDetail(NavHostFragment.findNavController(this), "initial"));
            buttons.addView(navigation);

            Button transaction = new Button(requireContext());
            transaction.setText("Open Fragment detail");
            transaction.setOnClickListener(view -> {
                FragmentDetail detail = new FragmentDetail();
                Bundle arguments = new Bundle();
                arguments.putString("value", "initial");
                detail.setArguments(arguments);
                requireActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.navigation_fixture_host, detail)
                        .addToBackStack("fragment_fixture_detail")
                        .commit();
            });
            buttons.addView(transaction);

            Button host = new Button(requireContext());
            host.setText("Open typed host panel");
            host.setOnClickListener(view -> {
                Bundle options = new Bundle();
                options.putString("region", "north");
                ((NavigationFixtureActivity) requireActivity()).showTypedPanel(27, options, true, "orange");
            });
            buttons.addView(host);

            Button compound = new Button(requireContext());
            compound.setText("Open compound transaction");
            compound.setOnClickListener(view -> {
                CompoundDetail detail = new CompoundDetail();
                Bundle arguments = new Bundle();
                arguments.putString("title", "orchard");
                arguments.putInt("count", 41);
                detail.setArguments(arguments);
                requireActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.navigation_fixture_host, detail)
                        .setPrimaryNavigationFragment(detail)
                        .addToBackStack("compound_fixture_detail")
                        .commit();
            });
            buttons.addView(compound);

            Button unsupported = new Button(requireContext());
            unsupported.setText("Open unsupported payload");
            unsupported.setOnClickListener(view -> {
                UnsupportedDetail detail = new UnsupportedDetail();
                Bundle arguments = new Bundle();
                arguments.putSerializable("payload", new OpaquePayload("payload shown"));
                detail.setArguments(arguments);
                requireActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.navigation_fixture_host, detail)
                        .addToBackStack("unsupported_fixture_detail")
                        .commit();
            });
            buttons.addView(unsupported);
            return buttons;
        }
    }

    public static final class TypedPanel extends Fragment {
        @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle state) {
            TextView label = new TextView(requireContext());
            label.setGravity(Gravity.CENTER);
            return label;
        }

        @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
            render();
        }

        void render() {
            Bundle arguments = getArguments();
            if (arguments == null) return;
            ((TextView) requireView()).setText("Typed host: title=" + arguments.getString("title")
                    + " count=" + arguments.getInt("count") + " enabled=" + arguments.getBoolean("enabled")
                    + " region=" + arguments.getString("region"));
        }
    }

    public static final class CompoundDetail extends Fragment {
        @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle state) {
            TextView label = new TextView(requireContext());
            label.setGravity(Gravity.CENTER);
            Bundle arguments = requireArguments();
            label.setText("Compound: title=" + arguments.getString("title") + " count=" + arguments.getInt("count"));
            return label;
        }
    }

    public static final class UnsupportedDetail extends Fragment {
        @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle state) {
            TextView label = new TextView(requireContext());
            label.setGravity(Gravity.CENTER);
            OpaquePayload payload = (OpaquePayload) requireArguments().getSerializable("payload");
            label.setText("Unsupported: " + payload.displayText);
            return label;
        }
    }

    private static final class OpaquePayload implements Serializable {
        final String displayText;

        OpaquePayload(String displayText) { this.displayText = displayText; }
    }

    public static final class FragmentDetail extends Fragment {
        @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle state) {
            TextView label = new TextView(requireContext());
            label.setGravity(Gravity.CENTER);
            label.setText("Fragment detail: " + requireArguments().getString("value"));
            return label;
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
