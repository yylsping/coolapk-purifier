package io.github.yylsping.coolapkpurifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 16.6.2 (qx4-shaped) D2 reply-sponsor verifier regression: the production
 * verifier must accept the qx4 contract and reject near-miss shapes that only
 * fail one deep structural check each.
 */
public final class D2ReplySponsorReplacement1662Test {
    private static ReplySponsorTargetSpec productionSpec() {
        TargetProfile profile =
                TestManifests.manifest().validatedProfileFor(TestManifests.COOLAPK_16_6_2);
        if (profile == null) {
            throw new AssertionError("bundled manifest must validate 16.6.2");
        }
        return profile.replySponsor;
    }

    private static ReplySponsorTargetSpec specForOwner(String owner) throws Exception {
        JSONObject json = new JSONObject()
                .put("ownerClass", owner)
                .put("methodName", "ވ")
                .put("layoutField", "މ")
                .put("entityClass", "com.coolapk.market.model.Entity")
                .put("entityTemplateGetter", "getEntityTemplate")
                .put("entityTemplate", "feedDetailReplySponsorCard")
                .put("adHelperClass", "com.coolapk.market.view.ad.EntityAdHelper")
                .put("bindingComponentClass", "androidx.databinding.DataBindingComponent")
                .put("viewHolderClass", "androidx.recyclerview.widget.RecyclerView$ViewHolder");
        return ReplySponsorTargetSpec.parse(json);
    }

    private static Method binderOf(String owner) throws Exception {
        Class<?> clazz = Class.forName(owner);
        return clazz.getDeclaredMethod("ވ", Object.class);
    }

    @Test
    public void acceptsQx4ShapedProductionContract() throws Exception {
        ReplySponsorTargetSpec spec = productionSpec();
        Method target = binderOf(spec.ownerClass);
        assertTrue(D2ReplySponsorReplacement.isExactTarget(
                spec, target, target.getDeclaringClass().getClassLoader()));
    }

    @Test
    public void rejectsInstanceLayoutField() throws Exception {
        ReplySponsorTargetSpec spec = specForOwner("qx4n1");
        Method target = binderOf("qx4n1");
        assertFalse(D2ReplySponsorReplacement.isExactTarget(
                spec, target, target.getDeclaringClass().getClassLoader()));
    }

    @Test
    public void rejectsConcreteParentBinder() throws Exception {
        ReplySponsorTargetSpec spec = specForOwner("qx4n2");
        Method target = binderOf("qx4n2");
        assertFalse(D2ReplySponsorReplacement.isExactTarget(
                spec, target, target.getDeclaringClass().getClassLoader()));
    }

    @Test
    public void rejectsConstructorWithoutBindingComponent() throws Exception {
        ReplySponsorTargetSpec spec = specForOwner("qx4n3");
        Method target = binderOf("qx4n3");
        assertFalse(D2ReplySponsorReplacement.isExactTarget(
                spec, target, target.getDeclaringClass().getClassLoader()));
    }

    @Test
    public void rejectsMissingEntityInstanceField() throws Exception {
        ReplySponsorTargetSpec spec = specForOwner("qx4n4");
        Method target = binderOf("qx4n4");
        assertFalse(D2ReplySponsorReplacement.isExactTarget(
                spec, target, target.getDeclaringClass().getClassLoader()));
    }
}
