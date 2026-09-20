package com.coolapk.market.view.ad;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Test stub for the abstract holder parent shape shared by the host
 * SponsorSelfDrawDetailViewHolder hierarchy (16.6.1 i5 / 16.6.2 ob).
 */
public abstract class SponsorDetailHolderParent extends RecyclerView.ViewHolder {
    protected SponsorDetailHolderParent(View itemView) {
        super(itemView);
    }

    public abstract void ވ(Object value);
}
