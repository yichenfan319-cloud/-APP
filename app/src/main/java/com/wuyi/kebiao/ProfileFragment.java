package com.wuyi.kebiao;

import android.content.Intent;
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

import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ProfileFragment extends Fragment {

    // ==================== 配置常量 ====================
    // 公开给 VersionChecker 复用，避免两处各写一份地址
    public static final String SERVER_BASE_URL = "http://47.105.75.20:3000";
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

        tvVersionName.setText("v" + VersionChecker.currentVersionName(requireContext()));
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

        // ==================== 自动检查更新开关 ====================
        MaterialSwitch autoCheck = view.findViewById(R.id.switch_auto_check);
        // 先设初值再挂监听，否则 setChecked 会触发监听把状态又写回去
        autoCheck.setChecked(VersionChecker.isAutoCheckEnabled(requireContext()));
        autoCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            VersionChecker.setAutoCheckEnabled(requireContext(), isChecked);
            // 点整行也能切换，方便单手操作
        });
        view.findViewById(R.id.layout_auto_check).setOnClickListener(v ->
                autoCheck.setChecked(!autoCheck.isChecked()));
    }

    // ==================== 原有方法 ====================
    // 版本读取统一走 VersionChecker，不再各写一份（原先这里两份逻辑与
    // VersionChecker 完全重复，且 getCurrentVersionCode 已无调用者）。

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
        // 复用 VersionChecker，避免两处各写一份检查逻辑（迟早不一致）。
        // 手动检查的语义：失败要明确提示，不能像启动自动检查那样静默。
        VersionChecker.manualCheck(requireContext());
    }

    private void sendFeedbackEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:" + FEEDBACK_EMAIL));
        intent.putExtra(Intent.EXTRA_SUBJECT, "武夷学院课表 App 反馈");
        intent.putExtra(Intent.EXTRA_TEXT, "\n\n\n\n---\nApp版本: v"
                + VersionChecker.currentVersionName(requireContext())
                + "\n设备: " + android.os.Build.MODEL);

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

    private void showErrorDialog(String message) {
        new AlertDialog.Builder(requireContext())
                .setTitle("提示")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
    }
}