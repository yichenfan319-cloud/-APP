package com.wuyi.kebiao;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ProfileFragment extends Fragment {

    // ==================== 配置常量 ====================
    private static final String SERVER_BASE_URL = "http://47.105.75.20:3000";
    private static final String FEEDBACK_EMAIL = "QQ3450541935@outlook.com";
    private static final String CREATOR_NAME = "星辰的网站";
    private static final String CREATOR_WEBSITE = "https://horizonos.icu";

    private TextView tvVersionName;
    private TextView tvAnnouncementStatus;

    private OkHttpClient client;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        client = new OkHttpClient();

        tvVersionName = view.findViewById(R.id.tv_version_name);
        TextView tvCreator = view.findViewById(R.id.tv_creator);
        tvAnnouncementStatus = view.findViewById(R.id.tv_announcement_status);

        tvVersionName.setText("v" + getCurrentVersionName());
        tvCreator.setText(CREATOR_NAME);

        // ==================== 设置点击事件 ====================

        // 公告
        view.findViewById(R.id.layout_announcement).setOnClickListener(v -> checkAnnouncement());

        // 版本更新
        view.findViewById(R.id.layout_version).setOnClickListener(v -> checkVersionUpdate());

        // 问题反馈
        view.findViewById(R.id.layout_feedback).setOnClickListener(v -> sendFeedbackEmail());

        // 创作者 - 点击跳转浏览器
        view.findViewById(R.id.layout_creator).setOnClickListener(v -> openCreatorWebsite());
    }

    // ==================== 原有方法 ====================

    private String getCurrentVersionName() {
        try {
            PackageInfo packageInfo = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "1.0";
        }
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo packageInfo = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 1;
        }
    }

    private void checkAnnouncement() {
        tvAnnouncementStatus.setText("加载中...");

        String url = SERVER_BASE_URL + "/api/announcement";
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                requireActivity().runOnUiThread(() -> {
                    tvAnnouncementStatus.setText("加载失败");
                    showErrorDialog("公告加载失败\n请检查网络");
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String title = json.optString("title", "公告");
                    String content = json.optString("content", "暂无公告内容");

                    requireActivity().runOnUiThread(() -> {
                        tvAnnouncementStatus.setText("已更新");
                        showAnnouncementDialog(title, content);
                    });
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() -> {
                        tvAnnouncementStatus.setText("解析失败");
                        showErrorDialog("公告解析失败");
                    });
                }
            }
        });
    }

    private void checkVersionUpdate() {
        String url = SERVER_BASE_URL + "/api/version";
        Request request = new Request.Builder().url(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "版本检查失败\n请检查网络", Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    int latestVersionCode = json.getInt("versionCode");
                    String latestVersionName = json.getString("versionName");
                    String updateUrl = json.optString("updateUrl", "");

                    int currentVersionCode = getCurrentVersionCode();

                    if (latestVersionCode > currentVersionCode) {
                        requireActivity().runOnUiThread(() ->
                                showUpdateDialog(latestVersionName, updateUrl)
                        );
                    } else {
                        requireActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        );
                    }
                } catch (Exception e) {
                    requireActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "版本检查失败", Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }

    private void sendFeedbackEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + FEEDBACK_EMAIL));
        intent.putExtra(Intent.EXTRA_SUBJECT, "武夷学院课表 App 反馈");
        intent.putExtra(Intent.EXTRA_TEXT, "\n\n\n\n---\nApp版本: v" + getCurrentVersionName() + "\n设备: " + android.os.Build.MODEL);

        try {
            startActivity(Intent.createChooser(intent, "选择邮箱应用"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "未找到邮箱应用，请手动发送邮件至 " + FEEDBACK_EMAIL, Toast.LENGTH_LONG).show();
        }
    }

    // 新增：打开创作者网站
    private void openCreatorWebsite() {
        if (CREATOR_WEBSITE.isEmpty()) {
            Toast.makeText(requireContext(), "网站地址未配置", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(CREATOR_WEBSITE));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "无法打开链接，请检查网络", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    // ==================== 弹窗方法 ====================

    private void showAnnouncementDialog(String title, String content) {
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("确定", null)
                .show();
    }

    private void showUpdateDialog(String latestVersion, String updateUrl) {
        new AlertDialog.Builder(requireContext())
                .setTitle("发现新版本 v" + latestVersion)
                .setMessage("是否前往下载更新？")
                .setPositiveButton("去更新", (dialog, which) -> {
                    if (!updateUrl.isEmpty()) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                        startActivity(intent);
                    } else {
                        Toast.makeText(requireContext(), "暂无下载链接，请稍后再试", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void showErrorDialog(String message) {
        new AlertDialog.Builder(requireContext())
                .setTitle("提示")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
    }
}