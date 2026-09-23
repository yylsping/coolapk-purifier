package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import androidx.databinding.DataBindingComponent;
import androidx.recyclerview.widget.RecyclerView;

import com.coolapk.market.model.$$AutoValue_Feed;
import com.coolapk.market.model.Entity;
import com.coolapk.market.model.EntityCard;
import com.coolapk.market.model.HolderItem;
import com.coolapk.market.view.cardlist.EntityListFragment;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface.Chain;

/** RELATED_DATA: original getter preservation and exact A/B/C UI contracts. */
public final class RelatedDataDeltaTest {
    @Test
    public void bothBundledProfilesPinTheSameVerifiedDescriptors() {
        TargetProfile p1661 = TestManifests.profile();
        TargetProfile p1662 = TestManifests.manifest()
                .validatedProfileFor(TestManifests.COOLAPK_16_6_2);
        assertEquals("Lcom/coolapk/market/model/$$AutoValue_Feed;->"
                        + "getRelatedData()Ljava/util/List;",
                p1661.relatedData.descriptor());
        assertEquals(p1661.relatedData.descriptor(), p1662.relatedData.descriptor());
        assertEquals("Lcom/coolapk/market/viewholder/ޕ;->ވ(Ljava/lang/Object;)V",
                p1661.relatedIconListUi.descriptor());
        assertEquals(p1661.relatedIconListUi.descriptor(),
                p1662.relatedIconListUi.descriptor());
        assertEquals("Lcom/coolapk/market/view/feed/reply/Ԯ;->"
                        + "ޚ(Lcom/coolapk/market/model/HolderItem;)V",
                p1661.relatedContentUi.descriptor());
        assertEquals(p1661.relatedContentUi.descriptor(),
                p1662.relatedContentUi.descriptor());
    }

    @Test
    public void getterContractIsConcreteListGetter() throws Exception {
        RelatedDataTargetSpec spec = TestManifests.profile().relatedData;
        Method exact = $$AutoValue_Feed.class.getDeclaredMethod("getRelatedData");
        assertTrue(RelatedDataDelta.isExactGetterTarget(spec, exact));
        assertFalse(RelatedDataDelta.isExactGetterTarget(spec,
                WrongGetter.class.getDeclaredMethod("getRelatedData")));
    }

    @Test
    public void getterAlwaysProceedsAndPreservesIdentity() throws Throwable {
        RelatedDataDelta delta = new RelatedDataDelta(null, new ModuleLog(null), null);
        List<String> original = Collections.singletonList("host");
        FakeChain chain = new FakeChain(null, original, null);

        assertSame(original, delta.onGetter(chain));
        assertEquals(1, chain.proceedCalls.get());
        assertTrue(delta.summaryLine().contains("related_getter_entered_count=1"));
        assertTrue(delta.summaryLine().contains(
                "related_original_return_preserved_count=1"));
    }

    @Test
    public void iconClassificationUsesTemplateAndNestedEntityTypeOnly() throws Exception {
        RelatedIconListUiTargetSpec spec = TestManifests.profile().relatedIconListUi;
        Method template = Entity.class.getMethod("getEntityTemplate");
        Method entities = EntityCard.class.getMethod("getEntities");
        Method type = Entity.class.getMethod("getEntityType");

        EntityCard goods = new EntityCard("iconListCard",
                Collections.singletonList(new Entity(null, "goods")));
        EntityCard topic = new EntityCard("iconListCard",
                Collections.singletonList(new Entity(null, "topic")));
        EntityCard mixed = new EntityCard("iconListCard", Arrays.asList(
                new Entity(null, "goods"), new Entity(null, "topic")));
        EntityCard nearby = new EntityCard("feedListCard",
                Collections.singletonList(new Entity(null, "goods")));

        assertEquals(RelatedDataDelta.IconSubtype.PROMOTION,
                RelatedDataDelta.classifyIconCard(spec, goods, EntityCard.class,
                        Entity.class, template, entities, type));
        assertEquals(RelatedDataDelta.IconSubtype.SINGLE_RECOMMEND,
                RelatedDataDelta.classifyIconCard(spec, topic, EntityCard.class,
                        Entity.class, template, entities, type));
        assertEquals(RelatedDataDelta.IconSubtype.NONE,
                RelatedDataDelta.classifyIconCard(spec, mixed, EntityCard.class,
                        Entity.class, template, entities, type));
        assertEquals(RelatedDataDelta.IconSubtype.NONE,
                RelatedDataDelta.classifyIconCard(spec, nearby, EntityCard.class,
                        Entity.class, template, entities, type));
    }

    @Test
    public void contentClassificationIsExactAndCaseSensitive() throws Exception {
        RelatedContentUiTargetSpec spec = TestManifests.profile().relatedContentUi;
        Method getter = HolderItem.class.getMethod("getEntityType");
        assertTrue(RelatedDataDelta.isExactContentEntity(spec,
                new HolderItem("ENTITY_TYPE_BIND_GOODS"), HolderItem.class, getter));
        assertFalse(RelatedDataDelta.isExactContentEntity(spec,
                new HolderItem("entity_type_bind_goods"), HolderItem.class, getter));
        assertFalse(RelatedDataDelta.isExactContentEntity(spec,
                new HolderItem("ENTITY_TYPE_BOTTOM"), HolderItem.class, getter));
    }

    @Test
    public void fixtureContractsRejectNearbyOwners() throws Exception {
        RelatedIconListUiTargetSpec icon = fixtureIconSpec();
        Method iconBinder = FixtureBaseHolder.class.getDeclaredMethod("bind", Object.class);
        assertTrue(RelatedDataDelta.isExactIconUiTarget(
                icon, iconBinder, getClass().getClassLoader()));
        assertFalse(RelatedDataDelta.isExactIconUiTarget(icon,
                NearbyBaseHolder.class.getDeclaredMethod("bind", Object.class),
                getClass().getClassLoader()));

        RelatedContentUiTargetSpec content = fixtureContentSpec();
        Method contentBinder = FixtureContentHolder.class
                .getDeclaredMethod("bindContent", HolderItem.class);
        assertTrue(RelatedDataDelta.isExactContentUiTarget(
                content, contentBinder, getClass().getClassLoader()));
        assertFalse(RelatedDataDelta.isExactContentUiTarget(content,
                NearbyContentHolder.class.getDeclaredMethod("bindContent", HolderItem.class),
                getClass().getClassLoader()));
    }

    @Test
    public void iconHostMustBeExactDetailFragment() throws Exception {
        RelatedDataDelta.IconRuntime runtime = fixtureIconRuntime();
        FixtureIconHolder detailHolder = new FixtureIconHolder(
                new View(null), null, new FixtureHostFragment());
        FixtureIconHolder nearbyHolder = new FixtureIconHolder(
                new View(null), null, new EntityListFragment());

        assertTrue(runtime.isExactDetailHost(detailHolder));
        assertFalse(runtime.isExactDetailHost(nearbyHolder));
        assertFalse(runtime.isExactDetailHost(new NearbyBaseHolder(new View(null))));
    }

    @Test
    public void promotionBinderProceedsCollapsesAndRestoresOnDisabledRebind()
            throws Throwable {
        RelatedDataDelta delta = new RelatedDataDelta(null, new ModuleLog(null), null);
        RelatedDataDelta.IconRuntime runtime = fixtureIconRuntime();
        View view = new StatefulView();
        view.setMinimumHeight(13);
        ViewGroup.LayoutParams iconParams = new ViewGroup.LayoutParams(100, 77);
        iconParams.height = 77;
        view.setLayoutParams(iconParams);
        FixtureIconHolder holder = new FixtureIconHolder(
                view, null, new FixtureHostFragment());
        EntityCard card = new EntityCard("iconListCard",
                Collections.singletonList(new Entity(null, "goods")));

        FakeChain enabled = new FakeChain(holder, null, card);
        delta.onIconUi(enabled, true, runtime);
        assertEquals(1, enabled.proceedCalls.get());
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(0, view.getMinimumHeight());
        assertEquals(0, view.getLayoutParams().height);

        FakeChain disabled = new FakeChain(holder, null, card);
        delta.onIconUi(disabled, false, runtime);
        assertEquals(1, disabled.proceedCalls.get());
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(13, view.getMinimumHeight());
        assertEquals(77, view.getLayoutParams().height);
    }

    @Test
    public void contentBinderProceedsAndUnknownHolderItemFailsOpen() throws Throwable {
        RelatedDataDelta delta = new RelatedDataDelta(null, new ModuleLog(null), null);
        RelatedDataDelta.ContentRuntime runtime = fixtureContentRuntime();
        View view = new StatefulView();
        ViewGroup.LayoutParams contentParams = new ViewGroup.LayoutParams(100, 51);
        contentParams.height = 51;
        view.setLayoutParams(contentParams);
        FixtureContentHolder holder = new FixtureContentHolder(view, null,
                new FixtureViewModel());

        FakeChain unknown = new FakeChain(holder, null,
                new HolderItem("ENTITY_TYPE_BOTTOM"));
        delta.onContentUi(unknown, true, runtime);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(51, view.getLayoutParams().height);

        FakeChain exact = new FakeChain(holder, null,
                new HolderItem("ENTITY_TYPE_BIND_GOODS"));
        delta.onContentUi(exact, true, runtime);
        assertEquals(View.GONE, view.getVisibility());
        assertEquals(0, view.getLayoutParams().height);
        assertTrue(delta.summaryLine().contains(
                "related_content_section_ui_suppressed=1"));
    }

    private RelatedDataDelta.IconRuntime fixtureIconRuntime() throws Exception {
        RelatedIconListUiTargetSpec spec = fixtureIconSpec();
        Method binder = FixtureBaseHolder.class.getDeclaredMethod("bind", Object.class);
        Field callback = FixtureBaseHolder.class.getDeclaredField("callback");
        Field fragment = FixtureIconCallback.class.getDeclaredField("fragment");
        callback.setAccessible(true);
        fragment.setAccessible(true);
        return new RelatedDataDelta.IconRuntime(spec, binder,
                FixtureIconHolder.class, FixtureIconCallback.class,
                FixtureHostFragment.class, EntityCard.class, Entity.class,
                callback, fragment,
                EntityCard.class.getMethod("getEntityTemplate"),
                EntityCard.class.getMethod("getEntities"),
                Entity.class.getMethod("getEntityType"),
                new RelatedDataDelta.HolderController(
                        FixtureIconHolder.class.getField("itemView")));
    }

    private RelatedDataDelta.ContentRuntime fixtureContentRuntime() throws Exception {
        RelatedContentUiTargetSpec spec = fixtureContentSpec();
        return new RelatedDataDelta.ContentRuntime(spec,
                FixtureContentHolder.class.getDeclaredMethod(
                        "bindContent", HolderItem.class),
                HolderItem.class, HolderItem.class.getMethod("getEntityType"),
                new RelatedDataDelta.HolderController(
                        FixtureContentHolder.class.getField("itemView")));
    }

    private static RelatedIconListUiTargetSpec fixtureIconSpec() throws Exception {
        return RelatedIconListUiTargetSpec.parse(new JSONObject()
                .put("ownerClass", FixtureBaseHolder.class.getName())
                .put("methodName", "bind")
                .put("holderClass", FixtureIconHolder.class.getName())
                .put("callbackField", "callback")
                .put("callbackClass", FixtureIconCallback.class.getName())
                .put("fragmentField", "fragment")
                .put("fragmentBaseClass", EntityListFragment.class.getName())
                .put("hostFragmentClass", FixtureHostFragment.class.getName())
                .put("bindingComponentClass", DataBindingComponent.class.getName())
                .put("viewHolderClass", RecyclerView.ViewHolder.class.getName())
                .put("cardClass", EntityCard.class.getName())
                .put("cardTemplateGetter", "getEntityTemplate")
                .put("cardTemplate", "iconListCard")
                .put("entitiesGetter", "getEntities")
                .put("entityClass", Entity.class.getName())
                .put("entityTypeGetter", "getEntityType")
                .put("promotionEntityType", "goods")
                .put("singleRecommendEntityType", "topic"));
    }

    private static RelatedContentUiTargetSpec fixtureContentSpec() throws Exception {
        return RelatedContentUiTargetSpec.parse(new JSONObject()
                .put("ownerClass", FixtureContentHolder.class.getName())
                .put("methodName", "bindContent")
                .put("dataClass", HolderItem.class.getName())
                .put("entityTypeGetter", "getEntityType")
                .put("entityType", "ENTITY_TYPE_BIND_GOODS")
                .put("bindingComponentClass", DataBindingComponent.class.getName())
                .put("viewModelClass", FixtureViewModel.class.getName())
                .put("viewHolderClass", RecyclerView.ViewHolder.class.getName()));
    }

    private static final class FakeChain implements Chain {
        final AtomicInteger proceedCalls = new AtomicInteger();
        private final Object thisObject;
        private final Object result;
        private final Object arg0;

        FakeChain(Object thisObject, Object result, Object arg0) {
            this.thisObject = thisObject;
            this.result = result;
            this.arg0 = arg0;
        }

        @Override public Executable getExecutable() { return null; }
        @Override public Object getThisObject() { return thisObject; }
        @Override public List<Object> getArgs() { return Collections.singletonList(arg0); }
        @Override public Object getArg(int index) { return index == 0 ? arg0 : null; }
        @Override public Object proceed() { proceedCalls.incrementAndGet(); return result; }
        @Override public Object proceed(Object... args) {
            proceedCalls.incrementAndGet(); return result;
        }
        @Override public Object proceedWith(Object thisObject) {
            proceedCalls.incrementAndGet(); return result;
        }
        @Override public Object proceedWith(Object thisObject, Object... args) {
            proceedCalls.incrementAndGet(); return result;
        }
    }

    public static class FixtureBaseHolder extends RecyclerView.ViewHolder {
        public final FixtureBaseCallback callback;

        public FixtureBaseHolder(View view, FixtureBaseCallback callback) {
            super(view);
            this.callback = callback;
        }

        public void bind(Object value) { }
    }

    public abstract static class FixtureBaseCallback { }

    public static final class FixtureIconCallback extends FixtureBaseCallback {
        public final EntityListFragment fragment;

        public FixtureIconCallback(EntityListFragment fragment) {
            this.fragment = fragment;
        }
    }

    public static final class FixtureIconHolder extends FixtureBaseHolder {
        public FixtureIconHolder(View view, DataBindingComponent component,
                                 EntityListFragment fragment) {
            super(view, new FixtureIconCallback(fragment));
        }
    }

    public static final class FixtureHostFragment extends EntityListFragment { }

    public static final class NearbyBaseHolder extends RecyclerView.ViewHolder {
        public NearbyBaseHolder(View view) { super(view); }
        public void bind(Object value) { }
    }

    public static final class FixtureViewModel { }

    public static final class FixtureContentHolder extends RecyclerView.ViewHolder {
        public FixtureContentHolder(View view, DataBindingComponent component,
                                    FixtureViewModel viewModel) {
            super(view);
        }

        public void bindContent(HolderItem item) { }
    }

    public static final class NearbyContentHolder extends RecyclerView.ViewHolder {
        public NearbyContentHolder(View view) { super(view); }
        public void bindContent(HolderItem item) { }
    }

    public static final class WrongGetter {
        public Object getRelatedData() { return null; }
    }

    private static final class StatefulView extends View {
        private int visibility = View.VISIBLE;
        private int minimumHeight;
        private ViewGroup.LayoutParams layoutParams;

        StatefulView() {
            super(null);
        }

        @Override public void setVisibility(int visibility) {
            this.visibility = visibility;
        }

        @Override public int getVisibility() {
            return visibility;
        }

        @Override public void setMinimumHeight(int minimumHeight) {
            this.minimumHeight = minimumHeight;
        }

        @Override public int getMinimumHeight() {
            return minimumHeight;
        }

        @Override public void setLayoutParams(ViewGroup.LayoutParams params) {
            this.layoutParams = params;
        }

        @Override public ViewGroup.LayoutParams getLayoutParams() {
            return layoutParams;
        }
    }
}
