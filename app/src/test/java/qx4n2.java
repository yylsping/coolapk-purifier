import android.view.View;

import androidx.databinding.DataBindingComponent;

import com.coolapk.market.model.Entity;
import com.coolapk.market.view.ad.EntityAdHelper;

/** Near-miss 16.6.2 qx4: parent binder is not abstract. */
public final class qx4n2 extends obn {
    public static int މ;
    private Entity entity;
    private final EntityAdHelper helper;

    public qx4n2(View itemView, EntityAdHelper helper, DataBindingComponent component) {
        super(itemView);
        this.helper = helper;
    }

    @Override
    public void ވ(Object value) {
        entity = (Entity) value;
    }
}
