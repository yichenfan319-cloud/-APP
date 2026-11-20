package com.wuyi.kebiao;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * 账号页：没验证过就给表单，验证过就只显示状态。
 *
 * <p>"验证"不是格式检查 —— 它会真的去登录一次教务处。一个存了但登不进去的
 * 密码，比起没存更耽误事（界面上写着"已设置"，查课却一直失败）。
 */
public class AccountFragment extends Fragment implements ScheduleStore.Listener {

    private TextView statusView;
    private TextView whoView;
    private View panelOk;
    private View panelForm;
    private TextInputEditText inputUser;
    private TextInputEditText inputPass;
    private MaterialButton verifyButton;
    private TextView hintView;

    private boolean verifying;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_account, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        statusView = view.findViewById(R.id.account_status);
        whoView = view.findViewById(R.id.account_who);
        panelOk = view.findViewById(R.id.panel_ok);
        panelForm = view.findViewById(R.id.panel_form);
        inputUser = view.findViewById(R.id.input_user);
        inputPass = view.findViewById(R.id.input_pass);
        verifyButton = view.findViewById(R.id.btn_verify);
        hintView = view.findViewById(R.id.verify_hint);

        verifyButton.setOnClickListener(v -> doVerify());

        ScheduleStore.get().addListener(this);
        render();
    }

    @Override
    public void onDestroyView() {
        ScheduleStore.get().removeListener(this);
        super.onDestroyView();
    }

    @Override
    public void onScheduleChanged() {
        if (!isAdded()) {
            return;
        }
        if (verifying) {
            ThinkingOrbView.State s = ScheduleStore.get().getState();
            if (s == ThinkingOrbView.State.DONE) {
                verifying = false;
                Persist.verifyOk(requireContext(), text(inputUser), text(inputPass));
                verifyButton.setEnabled(true);
                render();
                return;
            }
            if (s == ThinkingOrbView.State.ERROR) {
                verifying = false;
                verifyButton.setEnabled(true);
                hintView.setText(getString(R.string.verify_failed,
                        ScheduleStore.get().getDetail()));
                return;
            }
        }
        render();
    }

    private void doVerify() {
        String user = text(inputUser);
        String pass = text(inputPass);
        if (user.isEmpty() || pass.isEmpty()) {
            hintView.setText(R.string.verify_empty);
            return;
        }
        if (verifying) {
            return;
        }
        verifying = true;
        verifyButton.setEnabled(false);
        hintView.setText(R.string.verify_running);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).verifyAccount(user, pass);
        }
    }

    private void render() {
        if (statusView == null) {
            return;
        }
        Context ctx = requireContext();
        boolean verified = Persist.isVerified(ctx);
        statusView.setText(verified ? R.string.account_ok : R.string.account_missing);
        statusView.setTextColor(ContextCompat.getColor(ctx,
                verified ? R.color.status_ok : R.color.status_error));

        panelOk.setVisibility(verified ? View.VISIBLE : View.GONE);
        panelForm.setVisibility(verified ? View.GONE : View.VISIBLE);

        if (verified) {
            whoView.setText(getString(R.string.account_signed_as, Persist.username(ctx)));
        } else if (inputUser.getText() == null || inputUser.getText().length() == 0) {
            inputUser.setText(Persist.username(ctx));
            inputPass.setText(Persist.password(ctx));
        }
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
