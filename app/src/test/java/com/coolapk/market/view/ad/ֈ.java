package com.coolapk.market.view.ad;

import android.view.View;

import androidx.databinding.DataBindingComponent;

import com.coolapk.market.model.Entity;

/**
 * Test stub with the exact structural shape of the host
 * SponsorSelfDrawDetailViewHolder (com.coolapk.market.view.ad.ֈ):
 * final class, abstract parent binder, constructor argument order
 * (View, DataBindingComponent, EntityAdHelper), a static int layout field
 * and Entity/EntityAdHelper instance fields.
 */
public final class ֈ extends SponsorDetailHolderParent {
    public static int ކ;
    private Entity entity;
    private final EntityAdHelper helper;

    public ֈ(View itemView, DataBindingComponent component, EntityAdHelper helper) {
        super(itemView);
        this.helper = helper;
    }

    @Override
    public void ވ(Object value) {
        entity = (Entity) value;
    }
}
