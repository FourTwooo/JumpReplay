package com.fourtwo.hookintent.ui.me;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.fourtwo.hookintent.databinding.FragmentMeBinding;

import java.io.IOException;

import io.noties.markwon.Markwon;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MeFragment extends Fragment {

    private String TAG = "MeFragment";
    private FragmentMeBinding binding;
    private OkHttpClient client;
    private MeViewModel meViewModel;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化 OkHttpClient
        client = new OkHttpClient();
        // 使用 ViewModelProvider 获取 ViewModel 实例
        meViewModel = new ViewModelProvider(requireActivity()).get(MeViewModel.class);
    }

    private void fetchMarkdownFile(String url, TextView textView) {
        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @SuppressLint("SetTextI18n")
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                e.printStackTrace();
                // 检查 Fragment 是否附加到 Activity
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            textView.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                        }
                        textView.setText("请求失败：" + url);
                    });
                }
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String responseData = response.body().string();
                    meViewModel.setResponseData(responseData); // 缓存到 ViewModel

                    // 更新UI需要在主线程
                    requireActivity().runOnUiThread(() -> {
                        Markwon markwon = Markwon.create(requireContext());
                        markwon.setMarkdown(textView, responseData);
                    });
                } else {
                    // 如果响应不成功，处理为失败
                    requireActivity().runOnUiThread(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            textView.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                        }
                        textView.setText("请求失败：" + url);
                    });
                }
            }
        });
    }


    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textSlideshow;

        // 从 ViewModel 获取缓存的数据
        String cachedData = meViewModel.getResponseData();

        if (cachedData == null) {
            Log.d(TAG, "请求Github README.md");
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
