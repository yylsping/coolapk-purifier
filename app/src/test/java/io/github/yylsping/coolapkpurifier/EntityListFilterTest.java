package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public final class EntityListFilterTest {
    private EntityClassifier classifier;
    private EntityListFilter filter;

    @Before
    public void setUp() throws Exception {
        classifier = new EntityClassifier();
        classifier.setAccessors(new EntityAccessors(
                method("getEntityTemplate"),
                method("getEntityId"),
                method("getTitle"),
                method("getEntityType")));
        filter = new EntityListFilter(classifier);
    }

    private static Method method(String name) throws Exception {
        return Entity.class.getMethod(name);
    }

    @Test
    public void allLegacyRulesArePreserved() {
        assertTrue(classifier.isSponsored(new Entity("SponsorCard", "", "", "")));
        assertTrue(classifier.isSponsored(new Entity("", "SPONSORED-42", "", "")));
        assertTrue(classifier.isSponsored(new Entity("", "", "Feed_Detail_Reply_Sponsor_Card", "")));
        assertTrue(classifier.isSponsored(new Entity("NativeAdCard", "", "", "")));
        assertTrue(classifier.isSponsored(new Entity("advert_banner", "", "", "")));
        assertTrue(classifier.isSponsored(new Entity("replyAdCard", "", "", "")));
        assertTrue(classifier.isSponsored(new Entity("", "", "", "Advertisement")));
        assertTrue(classifier.isSponsored(new Entity("", "", "", "NATIVE_AD")));
    }

    @Test
    public void exactReplySponsorTemplateIsCarvedOutOnly() {
        Entity exact = new Entity("feedDetailReplySponsorCard", "sponsored-id",
                "sponsor title", "advertisement");
        Entity wrongCase = new Entity("FeedDetailReplySponsorCard", "", "", "");
        Entity otherSponsor = new Entity("sponsorCard", "", "", "");

        assertFalse(classifier.isSponsored(exact));
        assertTrue(classifier.isSponsored(wrongCase));
        assertTrue(classifier.isSponsored(otherSponsor));

        List<Entity> source = Arrays.asList(otherSponsor, exact);
        assertEquals(Arrays.asList(exact), filter.filter(source));
    }

    @Test
    public void nullMissingAndThrowingGettersFailOpen() {
        assertFalse(classifier.isSponsored(null));
        assertFalse(classifier.isSponsored(new Object()));
        assertFalse(classifier.isSponsored(new ThrowingEntity()));
    }

    @Test
    public void noAdReturnsTheOriginalList() {
        List<Entity> source = Arrays.asList(
                new Entity("feed", "1", "one", "feed"),
                new Entity("reply", "2", "two", "reply"));

        assertSame(source, filter.filter(source));
    }

    @Test
    public void filtersFirstMiddleLastAndMultipleAdsWithoutMutatingSource() {
        Entity first = new Entity("sponsor", "a", "ad-1", "feed");
        Entity one = new Entity("feed", "1", "one", "feed");
        Entity middle = new Entity("nativeAd", "b", "ad-2", "feed");
        Entity two = new Entity("feed", "2", "two", "feed");
        Entity last = new Entity("lastAdCard", "c", "ad-3", "feed");
        ArrayList<Entity> source = new ArrayList<>(Arrays.asList(first, one, middle, two, last));

        List<?> result = filter.filter(source);

        assertEquals(Arrays.asList(one, two), result);
        assertEquals(Arrays.asList(first, one, middle, two, last), source);
    }

    public static final class Entity {
        private final String template;
        private final String id;
        private final String title;
        private final String type;

        Entity(String template, String id, String title, String type) {
            this.template = template;
            this.id = id;
            this.title = title;
            this.type = type;
        }

        public String getEntityTemplate() {
            return template;
        }

        public String getEntityId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getEntityType() {
            return type;
        }
    }

    public static final class ThrowingEntity {
        public String getEntityTemplate() {
            throw new IllegalStateException("boom");
        }

        public String getEntityId() {
            return null;
        }

        public String getTitle() {
            return "ordinary";
        }

        public String getEntityType() {
            throw new IllegalStateException("boom");
        }
    }
}
