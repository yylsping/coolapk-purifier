import android.view.View;

import com.coolapk.market.model.Entity;
import com.coolapk.market.view.ad.EntityAdHelper;

/** Near-miss 16.6.2 qx4: constructor misses the DataBindingComponent parameter. */
public final class qx4n3 extends ob {
    public static int މ;
    private Entity entity;
    private final EntityAdHelper helper;

    public qx4n3(View itemView, EntityAdHelper helper) {
        super(itemView);
        this.helper = helper;
    }

    @Override
    public void ވ(Object value) {
        entity = (Entity) value;
    }
}
