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

import io.noties.markwon.Markwon;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler;
import io.noties.markwon.image.svg.SvgMediaDecoder;
import okhttp3.OkHttpClient;

import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.databinding.FragmentMeBinding;
import com.fourtwo.hookintent.utils.NetworkClient;
import com.fourtwo.hookintent.viewmodel.MeViewModel;

import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

public class MeFragment extends Fragment {

    private final String TAG = "MeFragment";
    private FragmentMeBinding binding;
    private MeViewModel meViewModel;
    private Markwon markwon;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化 ViewModel
        meViewModel = new ViewModelProvider(requireActivity()).get(MeViewModel.class);

        // 初始化 OkHttpClient
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true) // 重试机制
                .build();

        // 配置 Markwon
        markwon = Markwon.builder(requireContext())
                .usePlugin(HtmlPlugin.create()) // 支持 HTML 内容
                .usePlugin(TablePlugin.create(requireContext())) // 支持表格渲染
                .usePlugin(TaskListPlugin.create(requireContext())) // 支持任务列表
                .usePlugin(ImagesPlugin.create(plugin -> {
                    // 添加 SVG 支持
                    plugin.addMediaDecoder(SvgMediaDecoder.create());
                    // 使用 OkHttp 处理网络图片
                    plugin.addSchemeHandler(OkHttpNetworkSchemeHandler.create(okHttpClient));
                }))
                .build();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentMeBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final TextView textView = binding.textSlideshow;
        final ProgressBar progressBar = binding.progressBar;
        final TextView loadingText = binding.loadingText;

        // 获取缓存的 Markdown 数据
        String cachedData = meViewModel.getResponseData();

        if (cachedData == null) {
            Log.d(TAG, "请求 Github README.md");
            progressBar.setVisibility(View.VISIBLE);
            loadingText.setVisibility(View.VISIBLE);

            // 请求 Markdown 文件
            fetchMarkdownFile(Constants.GitHub_README_URL, textView);
        } else {
            Log.d(TAG, "使用缓存的 README.md");
            markwon.setMarkdown(textView, cachedData); // 渲染 Markdown
        }

        return root;
    }

    private void fetchMarkdownFile(String url, TextView textView) {
        new NetworkClient().getReadMe(url, new NetworkClient.ReadMeCallback() {
            @Override
            public void onSuccess(String data) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        markwon.setMarkdown(textView, data); // 渲染 Markdown
                        binding.progressBar.setVisibility(View.GONE);
                        binding.loadingText.setVisibility(View.GONE);
                    });
                }
                meViewModel.setResponseData(data); // 缓存数据到 ViewModel
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            textView.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                        }
                        textView.setText(errorMessage);
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
