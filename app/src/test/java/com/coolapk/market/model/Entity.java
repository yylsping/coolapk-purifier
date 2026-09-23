package com.coolapk.market.model;

public class Entity {
    private final String template;
    private final String entityType;

    public Entity() {
        this.template = null;
        this.entityType = null;
    }

    public Entity(String template) {
        this.template = template;
        this.entityType = null;
    }

    public Entity(String template, String entityType) {
        this.template = template;
        this.entityType = entityType;
    }

    public String getEntityTemplate() {
        return template;
    }

    public String getEntityType() {
        return entityType;
    }
}
