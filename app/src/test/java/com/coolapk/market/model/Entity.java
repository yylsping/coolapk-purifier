package com.coolapk.market.model;

public class Entity {
    private final String template;

    public Entity() {
        this.template = null;
    }

    public Entity(String template) {
        this.template = template;
    }

    public String getEntityTemplate() {
        return template;
    }
}
