package com.coolapk.market.model;

import java.util.List;

public final class EntityCard extends Entity {
    private final List<Entity> entities;

    public EntityCard(String template, List<Entity> entities) {
        super(template, "card");
        this.entities = entities;
    }

    public List<Entity> getEntities() {
        return entities;
    }
}
