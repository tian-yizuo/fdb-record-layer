/*
 * EliminateArithmeticValueWithConstantRule.java
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
import com.apple.foundationdb.record.query.plan.cascades.Column;
import com.apple.foundationdb.record.query.plan.cascades.matching.structure.BindingMatcher;
import com.apple.foundationdb.record.query.plan.cascades.values.RecordConstructorValue;
import com.apple.foundationdb.record.query.plan.cascades.values.Value;
import com.google.common.collect.ImmutableList;

import javax.annotation.Nonnull;

import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.AnyMatcher.any;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.MultiMatcher.all;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ValueMatchers.anyValue;
import static com.apple.foundationdb.record.query.plan.cascades.matching.structure.ValueMatchers.recordConstructorValue;

/**
 * A rule that eliminates an arithmetic operation. One of the following conditions have to be met:
 * <ul>
 * <li> this is an addition and one of the operands is constant. </li>
 * <li> TODO this is a multiplication and one of the operands is constant and the constant operand is greater than 0
 * </ul>
 */
@API(API.Status.EXPERIMENTAL)
@SuppressWarnings("PMD.TooManyStaticImports")
public class LiftConstructorRule extends ValueSimplificationRule<RecordConstructorValue> {
    @Nonnull
    private static final BindingMatcher<RecordConstructorValue> innerRecordMatcher =
            recordConstructorValue(all(anyValue()));

    @Nonnull
    private static final BindingMatcher<RecordConstructorValue> rootMatcher =
            recordConstructorValue(any(innerRecordMatcher));

    public LiftConstructorRule() {
        super(rootMatcher);
    }

    @Override
    public void onMatch(@Nonnull final ValueSimplificationRuleCall call) {
        final var bindings = call.getBindings();
        final var outerRecordConstructorValue = bindings.get(rootMatcher);
        final var innerRecordConstructorValue = bindings.get(innerRecordMatcher);

        if (!call.isRoot()) {
            return;
        }

        //
        // Go through the outer constructor's fields and expand them with the inner fields. Note that we assume that
        // there is no actual consumer of the result (other than order by clauses which is fine for what we are about
        // to do). We are changing the structure of the data with this simplification.
        //
        final var outerColumns = outerRecordConstructorValue.getColumns();
        final var innerColumns = innerRecordConstructorValue.getColumns();

        final var columnsBuilder = ImmutableList.<Column<? extends Value>>builder();

        for (final var column : outerColumns) {
            if (column.getValue() == innerRecordConstructorValue) {
                innerColumns.forEach(innerColumn -> columnsBuilder.add(Column.unnamedOf(innerColumn.getValue())));
            } else {
                columnsBuilder.add(Column.unnamedOf(column.getValue()));
            }
        }

        call.yield(RecordConstructorValue.ofColumns(columnsBuilder.build()));
    }
}
