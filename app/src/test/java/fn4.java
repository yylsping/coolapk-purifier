import android.view.View;

import androidx.databinding.DataBindingComponent;

import com.coolapk.market.model.Entity;
import com.coolapk.market.view.ad.EntityAdHelper;

public final class fn4 extends i5 {
    public static int \u0789;
    private Entity entity;
    private final EntityAdHelper helper;

    public fn4(View itemView, EntityAdHelper helper, DataBindingComponent component) {
        super(itemView);
        this.helper = helper;
    }

    @Override
    public void \u0788(Object value) {
        entity = (Entity) value;
    }
}
