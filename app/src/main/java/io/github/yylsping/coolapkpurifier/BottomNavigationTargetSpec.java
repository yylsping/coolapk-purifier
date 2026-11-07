package io.github.yylsping.coolapkpurifier;

import org.json.JSONObject;
import java.util.List;

/** Optional contract selected only through an exact validated host profile. */
final class BottomNavigationTargetSpec {
    final String rendererOwner, rendererMethod, configClass, itemClass, postSlotClass;
    final String viewModelClass, currentItemGetter, dataListGetter;
    final String helperClass, listGetter, instanceField;
    final String headerClass, avatarContainerField;
    final String glassRendererMethod;
    final String layoutOwner, layoutMethod;
    final String slotsMethod;
    final String refreshDataMethod;
    final List<String> rendererParams;
    final String panelRendererMethod;
    final List<String> panelRendererParams;

    private BottomNavigationTargetSpec(JSONObject json) throws TargetManifest.ManifestException {
        rendererOwner = required(json, "rendererOwner");
        rendererMethod = required(json, "rendererMethod");
        configClass = required(json, "configClass");
        itemClass = required(json, "itemClass");
        postSlotClass = required(json, "postSlotClass");
        viewModelClass = required(json, "viewModelClass");
        currentItemGetter = required(json, "currentItemGetter");
        dataListGetter = required(json, "dataListGetter");
        helperClass = required(json, "helperClass");
        listGetter = required(json, "listGetter");
        instanceField = required(json, "instanceField");
        headerClass = required(json, "headerClass");
        avatarContainerField = required(json, "avatarContainerField");
        glassRendererMethod = required(json, "glassRendererMethod");
        layoutOwner = required(json, "layoutOwner");
        layoutMethod = required(json, "layoutMethod");
        slotsMethod = required(json, "slotsMethod");
        refreshDataMethod = required(json, "refreshDataMethod");
        rendererParams = SpecJson.requiredArray(json, "rendererParams", "bottomNavigation");
        if (legacy()) {
            panelRendererMethod = required(json, "panelRendererMethod");
            panelRendererParams = SpecJson.requiredArray(json, "panelRendererParams", "bottomNavigation");
        } else {
            panelRendererMethod = null;
            panelRendererParams = java.util.Collections.emptyList();
        }
    }
    private static String required(JSONObject json, String key) throws TargetManifest.ManifestException {
        return SpecJson.required(json, key, "bottomNavigation");
    }
    static BottomNavigationTargetSpec parse(JSONObject json) throws TargetManifest.ManifestException {
        return json == null ? null : new BottomNavigationTargetSpec(json);
    }
    boolean legacy() { return rendererParams.size() == 9; }
}
