package androidx.recyclerview.widget;

import android.view.View;

public class RecyclerView {
    public abstract static class ViewHolder {
        public final View itemView;

        protected ViewHolder(View itemView) {
            this.itemView = itemView;
        }
    }
}
