package io.github.yylsping.coolapkpurifier;

import com.coolapk.market.model.Entity;
import com.coolapk.market.view.cardlist.MainV8ListFragment;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class D3SameTopicReplacementTest {
    @Test
    public void manifestSpecMatchesBaselineDescriptors() {
        SameTopicTargetSpec spec = TestManifests.profile().sameTopic;
        assertEquals("Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                        + "ઽ(Ljava/lang/Object;)Z",
                spec.semanticDescriptor());
        assertEquals("Lcom/coolapk/market/view/cardlist/MainV8ListFragment;->"
                        + "onInsertRecommendListEvent(Lyl6;)V",
                spec.eventDescriptor());
        assertEquals("feedRecommendListCard", spec.entityTemplate);
        assertEquals("Ϳ", spec.anchorGetter);
        assertEquals("Ԩ", spec.cardGetter);
    }

    @Test
    public void validatesOnlyPinnedSemanticTarget() throws Exception {
        SameTopicTargetSpec spec = TestManifests.profile().sameTopic;
        Method exact = MainV8ListFragment.class.getMethod("ઽ", Object.class);
        Method wrong = MainV8ListFragment.class.getMethod("wrong", Object.class);

        assertTrue(D3SameTopicReplacement.isExactSemanticTarget(spec, exact));
        assertFalse(D3SameTopicReplacement.isExactSemanticTarget(spec, wrong));
    }

    @Test
    public void exactFilterIsLazyAndOrderPreserving() throws Exception {
        SameTopicTargetSpec spec = TestManifests.profile().sameTopic;
        Method getter = Entity.class.getMethod("getEntityTemplate");
        Entity ordinary = new Entity("feed");
        Entity exact = new Entity("feedRecommendListCard");
        Entity nearMiss = new Entity("feedRecommendListCardUserPost");
        List<Object> source = Arrays.asList(ordinary, exact, "unknown", nearMiss);

        List<?> filtered = D3SameTopicReplacement.filterSameTopicCards(
                spec, source, Entity.class, getter);

        assertNotSame(source, filtered);
        assertEquals(Arrays.asList(ordinary, "unknown", nearMiss), filtered);
        List<Entity> noMatch = Collections.singletonList(nearMiss);
        assertSame(noMatch, D3SameTopicReplacement.filterSameTopicCards(
                spec, noMatch, Entity.class, getter));
        assertSame(noMatch, D3SameTopicReplacement.filterSameTopicCards(
                spec, noMatch, Entity.class, null));
    }
}
