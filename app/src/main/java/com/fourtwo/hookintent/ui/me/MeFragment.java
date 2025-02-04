package com.fourtwo.hookintent.ui.me;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.fourtwo.hookintent.databinding.FragmentMeBinding;
import com.fourtwo.hookintent.tools.NetworkClient;

import io.noties.markwon.Markwon;

public class MeFragment extends Fragment {

    private final String TAG = "MeFragment";
    private FragmentMeBinding binding;
    private MeViewModel meViewModel;
    private NetworkClient networkClient;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 使用 ViewModelProvider 获取 ViewModel 实例
        meViewModel = new ViewModelProvider(requireActivity()).get(MeViewModel.class);
        // 初始化 NetworkClient
        networkClient = new NetworkClient();
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textSlideshow;
        final ProgressBar progressBar = binding.progressBar;
        final TextView loadingText = binding.loadingText;

        // 从 ViewModel 获取缓存的数据
        String cachedData = meViewModel.getResponseData();

        if (cachedData == null) {
            Log.d(TAG, "请求Github README.md");
            // 显示加载中的 ProgressBar 和提示文本
            progressBar.setVisibility(View.VISIBLE);
            loadingText.setVisibility(View.VISIBLE);

            // 请求Markdown文件内容
            String url = "https://raw.githubusercontent.com/FourTwooo/JumpReplay/refs/heads/master/README.md";
            fetchMarkdownFile(url, textView);
        } else {
            Log.d(TAG, "使用缓存的 README.md");
            Markwon markwon = Markwon.create(requireContext());
            markwon.setMarkdown(textView, cachedData);
        }

        return root;
    }

    private void fetchMarkdownFile(String url, TextView textView) {
        networkClient.getReadMe(url, new NetworkClient.ReadMeCallback() {
            @Override
            public void onSuccess(String data) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        Markwon markwon = Markwon.create(requireContext());
                        markwon.setMarkdown(textView, data);
                        // 隐藏加载中的 ProgressBar 和提示文本
                        binding.progressBar.setVisibility(View.GONE);
                        binding.loadingText.setVisibility(View.GONE);
                    });
                }
                meViewModel.setResponseData(data); // 缓存到 ViewModel
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            textView.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                        }
                        textView.setText(errorMessage);
                        // 隐藏加载中的 ProgressBar 和提示文本
                        binding.progressBar.setVisibility(View.GONE);
                        binding.loadingText.setVisibility(View.GONE);
                    });
                }
            }
        });
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
