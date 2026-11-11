package com.fourtwo.hookintent.ui.history;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ReleaseInfo;
import com.fourtwo.hookintent.utils.NetworkClient;

import java.util.ArrayList;
import java.util.List;

public class HistoryFragment extends Fragment {

    private final NetworkClient networkClient = new NetworkClient();

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyView;
    private HistoryAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.recycler_history);
        progressBar = view.findViewById(R.id.progress_history);
        emptyView = view.findViewById(R.id.empty_history);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new HistoryAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        loadHistory();
    }

    private void loadHistory() {
        final View expectedView = getView();
        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText(getString(R.string.history_loading));

        networkClient.getReleasesCached(requireContext(), Constants.GitHub_RELEASES_URL, false, new NetworkClient.ReleaseListCallback() {
            @Override
            public void onSuccess(List<ReleaseInfo> releases) {
                if (!isAdded() || getView() == null || getView() != expectedView) return;
                progressBar.setVisibility(View.GONE);

                if (releases == null || releases.isEmpty()) {
                    recyclerView.setVisibility(View.GONE);
                    emptyView.setVisibility(View.VISIBLE);
                    emptyView.setText(getString(R.string.history_empty));
                    return;
                }

                adapter.updateData(releases);
                recyclerView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(View.GONE);
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!isAdded() || getView() == null || getView() != expectedView) return;
                progressBar.setVisibility(View.GONE);
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
                emptyView.setText(getString(R.string.history_error, errorMessage));
            }
        });
    }

    @Override public void onDestroyView() {
        recyclerView = null;
        progressBar = null;
        emptyView = null;
        adapter = null;
        super.onDestroyView();
    }
}
