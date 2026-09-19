import android.view.View;

import androidx.databinding.DataBindingComponent;

import com.coolapk.market.model.Entity;
import com.coolapk.market.view.ad.EntityAdHelper;

/** 16.6.2 shape: Lqx4; — reply-sponsor ViewHolder (fn4 renamed). */
public final class qx4 extends ob {
    public static int މ;
    private Entity entity;
    private final EntityAdHelper helper;

    public qx4(View itemView, EntityAdHelper helper, DataBindingComponent component) {
        super(itemView);
        this.helper = helper;
    }

    @Override
    public void ވ(Object value) {
        entity = (Entity) value;
    }
}
