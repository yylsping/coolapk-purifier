import androidx.compose.runtime.Composer;

import com.coolapk.market.model.Feed;

/** 16.6.2 shape: Lvb4; — topic/device recommend static assembler (d14 renamed). */
public final class vb4 {
    private vb4() {
    }

    public static kotlin.Unit ޓ(Feed feed, vb4 self, Composer composer, int flags) {
        return kotlin.Unit.INSTANCE;
    }

    /** Near-miss: second parameter is not the owner-self type. */
    public static kotlin.Unit ޓselfmismatch(Feed feed, Object self, Composer composer,
                                            int flags) {
        return kotlin.Unit.INSTANCE;
    }

    /** Near-miss: void return instead of kotlin.Unit. */
    public static void ޓretmismatch(Feed feed, vb4 self, Composer composer, int flags) {
    }

    /** Near-miss: instance method instead of static. */
    public kotlin.Unit ޓinst(Feed feed, vb4 self, Composer composer, int flags) {
        return kotlin.Unit.INSTANCE;
    }
}
