/*
 * SumAggregateValue.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2021 Apple Inc. and the FoundationDB project authors
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

package com.apple.foundationdb.record.query.predicates;

import com.apple.foundationdb.record.cursors.aggregate.AggregateAccumulators;

import javax.annotation.Nonnull;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A utility class to offer specific instances of {@link AggregateValue}s.
 */
public class AggregateValues {

    // ------------------ SUM Aggregators ------------------------------

    /**
     * Aggregate value for Sum of Integers.
     */
    public static AggregateValue<Integer, Integer> sumInt(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.SUM, AggregateAccumulators::sumInteger);
    }

    /**
     * Aggregate value for Sum of Longs.
     */
    public static AggregateValue<Long, Long> sumLong(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.SUM, AggregateAccumulators::sumLong);
    }

    /**
     * Aggregate value for Sum of Floats.
     */
    public static AggregateValue<Float, Float> sumFloat(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.SUM, AggregateAccumulators::sumFloat);
    }

    /**
     * Aggregate value for Sum of Doubles.
     */
    public static AggregateValue<Double, Double> sumDouble(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.SUM, AggregateAccumulators::sumDouble);
    }

    // ------------------ MIN Aggregators ------------------------------

    /**
     * Aggregate value for Min of Integers.
     */
    public static AggregateValue<Integer, Integer> minInt(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MIN, AggregateAccumulators::minInteger);
    }

    /**
     * Aggregate value for Min of Longs.
     */
    public static AggregateValue<Long, Long> minLong(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MIN, AggregateAccumulators::minLong);
    }

    /**
     * Aggregate value for Min of Floats.
     */
    public static AggregateValue<Float, Float> minFloat(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MIN, AggregateAccumulators::minFloat);
    }

    /**
     * Aggregate value for Min of Doubles.
     */
    public static AggregateValue<Double, Double> minDouble(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MIN, AggregateAccumulators::minDouble);
    }

    // ------------------ MAX Aggregators ------------------------------

    /**
     * Aggregate value for Max of Integers.
     */
    public static AggregateValue<Integer, Integer> maxInt(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MAX, AggregateAccumulators::maxInteger);
    }

    /**
     * Aggregate value for Max of Longs.
     */
    public static AggregateValue<Long, Long> maxLong(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MAX, AggregateAccumulators::maxLong);
    }

    /**
     * Aggregate value for Max of Floats.
     */
    public static AggregateValue<Float, Float> maxFloat(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MAX, AggregateAccumulators::maxFloat);
    }

    /**
     * Aggregate value for Max of Doubles.
     */
    public static AggregateValue<Double, Double> maxDouble(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.MAX, AggregateAccumulators::maxDouble);
    }

    // ------------------ AVERAGE Aggregators ------------------------------

    public static AggregateValue<Integer, Double> averageInt(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.AVG, AggregateAccumulators::averageInt);
    }

    public static AggregateValue<Long, Double> averageLong(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.AVG, AggregateAccumulators::averageLong);
    }

    public static AggregateValue<Float, Double> averageFloat(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.AVG, AggregateAccumulators::averageFloat);
    }

    public static AggregateValue<Double, Double> averageDouble(@Nonnull Value child) {
        return new AggregateValue<>(child, AggregateValue.AggregateType.AVG, AggregateAccumulators::averageDouble);
    }
}
