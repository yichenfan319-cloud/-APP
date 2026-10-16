package com.wuyi.kebiao;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 开源许可列表适配器。
 *
 * 两种行：分组标题 / 组件条目。用 RecyclerView 而不是把十几条写死在 XML 里 ——
 * 后者以后加一个依赖就要改布局，容易漏。
 */
public class LicenseAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<LicenseEntry> items;

    public LicenseAdapter(List<LicenseEntry> items) {
        this.items = items;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).header ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_license_header, parent, false));
        }
        return new ItemHolder(inflater.inflate(R.layout.item_license, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        LicenseEntry e = items.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).title.setText(e.name);
        } else {
            ItemHolder h = (ItemHolder) holder;
            h.name.setText(e.title());
            h.license.setText(e.license);
            h.copyright.setText(e.copyright);
            // 没有版权说明时隐藏该行，避免留下空白
            h.copyright.setVisibility(e.copyright.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.lic_header);
        }
    }

    static class ItemHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView license;
        final TextView copyright;

        ItemHolder(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.lic_name);
            license = v.findViewById(R.id.lic_license);
            copyright = v.findViewById(R.id.lic_copyright);
        }
    }
}
