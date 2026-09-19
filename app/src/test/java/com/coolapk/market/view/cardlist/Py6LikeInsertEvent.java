package com.coolapk.market.view.cardlist;

import java.util.List;

/**
 * 16.6.2 py6-shaped same-topic insert event payload. Packaged test fixture
 * because Java sources cannot reference default-package host classes; the real
 * production descriptor Lpy6; stays pinned by TargetManifest1662ProfileTest.
 */
public final class Py6LikeInsertEvent {
    private final String anchor;
    private final List<Object> cards;

    public Py6LikeInsertEvent(String anchor, List<Object> cards) {
        this.anchor = anchor;
        this.cards = cards;
    }

    public String Ϳ() {
        return anchor;
    }

    public List Ԩ() {
        return cards;
    }
}
