/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.health.node.check;

import org.elasticsearch.health.node.LocalHealthMonitor;
import org.elasticsearch.health.node.UpdateHealthInfoCacheAction;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Interface for health trackers that will be executed by the {@link LocalHealthMonitor}. It keeps track of the last
 * reported value and can retrieve the current health status when requested. It provides helper methods to update
 *
 * @param <T> the type that the health check they track.
 */
public interface HealthTracker<T> {
    /**
     * Determine the health info for this health check.
     *
     * @return the health info.
     */
    T checkCurrentHealth();

    T getLastReportedHealth();

    boolean updateLastReportedHealth(T previous, T current);

    /**
     * Add the health info to the request builder.
     *
     * @param builder the builder to add the health info to.
     * @param healthInfo the health info to add.
     */
    void addToRequestBuilder(UpdateHealthInfoCacheAction.Request.Builder builder, T healthInfo);

    default HealthProgress<T> trackHealth() {
        return new HealthProgress<>(
            getLastReportedHealth(),
            checkCurrentHealth(),
            this::addToRequestBuilder,
            this::updateLastReportedHealth
        );
    }

    void reset();

    /**
     * A record for storing the previousHealth and current value of a health check. This allows us to be sure no concurrent processes have
     * updated the health check's reference value.
     *
     * @param <T> the type that the health check returns
     */
    class HealthProgress<T> {
        private final T previousHealth;
        private final T currentHealth;
        private final BiConsumer<UpdateHealthInfoCacheAction.Request.Builder, T> updateRequestBuilder;
        private final BiConsumer<T, T> maybeUpdateReportedValue;

        HealthProgress(
            T previousHealth,
            T currentHealth,
            BiConsumer<UpdateHealthInfoCacheAction.Request.Builder, T> updateRequestBuilder,
            BiConsumer<T, T> maybeUpdateReportedValue
        ) {
            this.previousHealth = previousHealth;
            this.currentHealth = currentHealth;
            this.updateRequestBuilder = updateRequestBuilder;
            this.maybeUpdateReportedValue = maybeUpdateReportedValue;
        }

        public boolean hasChanged() {
            return Objects.equals(previousHealth, currentHealth);
        }

        public void updateRequestBuilder(UpdateHealthInfoCacheAction.Request.Builder builder) {
            updateRequestBuilder.accept(builder, currentHealth);
        }

        public void recordProgress() {
            maybeUpdateReportedValue.accept(previousHealth, currentHealth);
        }
    }
}
