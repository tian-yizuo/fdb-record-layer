/*
 * AbstractValueRuleCall.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2022 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.query.plan.cascades.values.simplification;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.query.plan.cascades.CorrelationIdentifier;
import com.apple.foundationdb.record.query.plan.cascades.LinkedIdentitySet;
import com.apple.foundationdb.record.query.plan.cascades.PlannerRuleCall;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.PlannerBindings;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * A rule call implementation for the simplification of {@link Value} trees. This rule call implements the logic for
 * handling new {@link Value}s as they are generated by a {@link AbstractValueRule#onMatch(PlannerRuleCall)} and
 * passed to the rule call via the {@link #yield(R)} method.
 * @param <R> the type parameter representing the type of result that is handed to {@link #yield(R)}
 * @param <C> the type of `this`
 *
 */
@API(API.Status.EXPERIMENTAL)
public class AbstractValueRuleCall<R, C extends AbstractValueRuleCall<R, C>> implements PlannerRuleCall<R> {
    @Nonnull
    private final AbstractValueRule<R, C, ? extends Value> rule;
    @Nonnull
    private final Value current;
    @Nonnull
    private final PlannerBindings bindings;
    @Nonnull
    private final Set<CorrelationIdentifier> constantAliases;
    @Nonnull
    private final LinkedIdentitySet<R> results;

    public AbstractValueRuleCall(@Nonnull final AbstractValueRule<R, C, ? extends Value> rule,
                                 @Nonnull final Value current,
                                 @Nonnull final PlannerBindings bindings,
                                 @Nonnull final Set<CorrelationIdentifier> constantAliases) {
        this.rule = rule;
        this.current = current;
        this.bindings = bindings;
        this.results = new LinkedIdentitySet<>();
        this.constantAliases = ImmutableSet.copyOf(constantAliases);
    }

    @Nonnull
    public Value getCurrent() {
        return current;
    }

    @Nonnull
    public AbstractValueRule<R, C, ? extends Value> getRule() {
        return rule;
    }

    @Override
    @Nonnull
    public PlannerBindings getBindings() {
        return bindings;
    }

    @Nonnull
    public Set<CorrelationIdentifier> getConstantAliases() {
        return constantAliases;
    }

    @Override
    @SuppressWarnings("PMD.CompareObjectsWithEquals") // deliberate use of == equality check for short-circuit condition
    public void yield(@Nonnull R value) {
        if (value == current) {
            return;
        }

        results.add(value);
    }

    @Nonnull
    public Collection<R> getResults() {
        return Collections.unmodifiableCollection(results);
    }
}
