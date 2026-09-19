package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.coolapk.market.model.Entity;
import com.coolapk.market.view.cardlist.EntityListFragment;
import com.coolapk.market.view.cardlist.MainV8ListFragment;
import com.coolapk.market.view.cardlist.Py6LikeInsertEvent;
import com.coolapk.market.view.cardlist.SameTopicOrphanOwner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 16.6.2 (py6 / ཥ-shaped) D3 same-topic verifier regression. Java sources
 * cannot reference the default-package py6, so packaged fixtures carry the
 * same contract shape; TargetManifest1662ProfileTest pins the real Lpy6;
 * descriptor in the bundled manifest.
 */
public final class D3SameTopicReplacement1662Test {
    private static SameTopicTargetSpec spec1662() throws Exception {
        return spec1662("ཥ", "onInsertRecommendListEvent",
                MainV8ListFragment.class.getName());
    }

    private static SameTopicTargetSpec spec1662(String semanticMethod,
                                                String eventHandler,
                                                String ownerClass) throws Exception {
        JSONObject json = new JSONObject()
                .put("ownerClass", ownerClass)
                .put("ownerParentClass", EntityListFragment.class.getName())
                .put("semanticMethod", semanticMethod)
                .put("eventClass", Py6LikeInsertEvent.class.getName())
                .put("eventHandler", eventHandler)
                .put("anchorGetter", "Ϳ")
                .put("cardGetter", "Ԩ")
                .put("entityClass", "com.coolapk.market.model.Entity")
                .put("entityTemplateGetter", "getEntityTemplate")
                .put("entityTemplate", "feedRecommendListCard");
        return SameTopicTargetSpec.parse(json);
    }

    @Test
    public void accepts1662SemanticContract() throws Exception {
        Method exact = MainV8ListFragment.class.getMethod("ཥ", Object.class);
        assertTrue(D3SameTopicReplacement.isExactSemanticTarget(spec1662(), exact));
    }

    @Test
    public void rejectsSemanticNameDrift() throws Exception {
        Method wrong = MainV8ListFragment.class.getMethod("wrong", Object.class);
        assertFalse(D3SameTopicReplacement.isExactSemanticTarget(spec1662(), wrong));
    }

    @Test
    public void rejectsNonStaticSemantic() throws Exception {
        SameTopicTargetSpec spec = spec1662("ཥnonstatic",
                "onInsertRecommendListEvent", MainV8ListFragment.class.getName());
        Method instance = MainV8ListFragment.class.getMethod("ཥnonstatic", Object.class);
        assertFalse(D3SameTopicReplacement.isExactSemanticTarget(spec, instance));
    }

    @Test
    public void rejectsSemanticReturnTypeDrift() throws Exception {
        SameTopicTargetSpec spec = spec1662("ཥbadreturn",
                "onInsertRecommendListEvent", MainV8ListFragment.class.getName());
        Method badReturn = MainV8ListFragment.class.getMethod("ཥbadreturn", Object.class);
        assertFalse(D3SameTopicReplacement.isExactSemanticTarget(spec, badReturn));
    }

    @Test
    public void rejectsOwnerOutsideEntityListFragmentHierarchy() throws Exception {
        SameTopicTargetSpec spec = spec1662("ཥ",
                "onInsertRecommendListEvent", SameTopicOrphanOwner.class.getName());
        Method orphan = SameTopicOrphanOwner.class.getMethod("ཥ", Object.class);
        assertFalse(D3SameTopicReplacement.isExactSemanticTarget(spec, orphan));
    }

    @Test
    public void accepts1662EventHandlerContract() throws Exception {
        Method handler = MainV8ListFragment.class.getDeclaredMethod(
                "onInsertRecommendListEvent", Py6LikeInsertEvent.class);
        assertTrue(D3SameTopicReplacement.isExactEventTarget(
                spec1662(), handler, Py6LikeInsertEvent.class));
    }

    @Test
    public void rejectsEventHandlerParameterDrift() throws Exception {
        SameTopicTargetSpec spec = spec1662("ཥ",
                "onInsertRecommendListEventBadParam", MainV8ListFragment.class.getName());
        Method badParam = MainV8ListFragment.class.getDeclaredMethod(
                "onInsertRecommendListEventBadParam", Object.class);
        assertFalse(D3SameTopicReplacement.isExactEventTarget(
                spec, badParam, Py6LikeInsertEvent.class));
    }

    @Test
    public void payloadAccessorsAndConstructorMatch1662Shape() throws Exception {
        Method anchorGetter = Py6LikeInsertEvent.class.getDeclaredMethod("Ϳ");
        Method cardGetter = Py6LikeInsertEvent.class.getDeclaredMethod("Ԩ");
        Constructor<?> constructor = Py6LikeInsertEvent.class
                .getDeclaredConstructor(String.class, List.class);

        assertEquals(String.class, anchorGetter.getReturnType());
        assertEquals(0, anchorGetter.getParameterCount());
        assertEquals(List.class, cardGetter.getReturnType());
        assertEquals(0, cardGetter.getParameterCount());

        Entity ordinary = new Entity("feed");
        Entity recommend = new Entity("feedRecommendListCard");
        List<Object> source = Arrays.asList(ordinary, recommend, "unknown");
        Py6LikeInsertEvent event = new Py6LikeInsertEvent("anchor-1", source);

        assertEquals("anchor-1", anchorGetter.invoke(event));
        List<?> cards = (List<?>) cardGetter.invoke(event);
        List<?> filtered = D3SameTopicReplacement.filterSameTopicCards(spec1662(),
                cards, Entity.class, Entity.class.getMethod("getEntityTemplate"));
        assertNotSame(cards, filtered);
        assertEquals(Arrays.asList(ordinary, "unknown"), filtered);

        // Reconstruction path used by the production interceptor.
        Py6LikeInsertEvent replaced = (Py6LikeInsertEvent) constructor
                .newInstance(anchorGetter.invoke(event), filtered);
        assertEquals("anchor-1", replaced.Ϳ());
        assertEquals(2, replaced.Ԩ().size());
    }
}
