/*
 * LucenePlanner.java
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

package com.apple.foundationdb.record.lucene;

import com.apple.foundationdb.record.IndexScanType;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordStoreState;
import com.apple.foundationdb.record.metadata.Index;
import com.apple.foundationdb.record.metadata.RecordType;
import com.apple.foundationdb.record.metadata.expressions.GroupingKeyExpression;
import com.apple.foundationdb.record.metadata.expressions.KeyExpression;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoreTimer;
import com.apple.foundationdb.record.query.QueryToKeyMatcher;
import com.apple.foundationdb.record.query.expressions.AndComponent;
import com.apple.foundationdb.record.query.expressions.Comparisons;
import com.apple.foundationdb.record.query.expressions.FieldWithComparison;
import com.apple.foundationdb.record.query.expressions.LuceneQueryComponent;
import com.apple.foundationdb.record.query.expressions.NestedField;
import com.apple.foundationdb.record.query.expressions.OneOfThemWithComponent;
import com.apple.foundationdb.record.query.expressions.OrComponent;
import com.apple.foundationdb.record.query.expressions.QueryComponent;
import com.apple.foundationdb.record.query.plan.PlannableIndexTypes;
import com.apple.foundationdb.record.query.plan.RecordQueryPlanner;
import com.apple.foundationdb.record.query.plan.ScanComparisons;
import com.apple.foundationdb.record.query.plan.planning.FilterSatisfiedMask;
import com.apple.foundationdb.record.query.plan.plans.RecordQueryPlan;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A planner to implement lucene query planning so that we can isolate the lucene functionality to
 * a distinct package. This was necessary because of the need to pass the sort key expression into the
 * plan created and from there into the record cursor which creates a Lucene sort object.
 */
public class LucenePlanner extends RecordQueryPlanner {

    public LucenePlanner(@Nonnull final RecordMetaData metaData, @Nonnull final RecordStoreState recordStoreState, final PlannableIndexTypes indexTypes, final FDBStoreTimer timer) {
        super(metaData, recordStoreState, indexTypes, timer);
    }

    private LuceneIndexQueryPlan getScanForFieldWithComparison(@Nonnull Index index, @Nullable String parentFieldName, @Nonnull FieldWithComparison filter,
                                                               @Nullable FilterSatisfiedMask filterSatisfiedMask, final ScanComparisons groupingComparisons) {
        String completeFieldName = filter.getFieldName();
        Comparisons.Comparison comparison =  filter.getComparison();
        if (groupingComparisons != null && groupingComparisons.getEqualityComparisons().contains(comparison)) {
            return null;
        }
        if (parentFieldName != null) {
            completeFieldName = parentFieldName + "_" + completeFieldName;
        }
        if (!validateIndexField(index, completeFieldName, true)) {
            return null;
        }
        String comparisonString;
        Comparisons.Type type = filter.getComparison().getType();
        switch (type) {
            case EQUALS:
                comparisonString = "%s:";
                break;
            case TEXT_CONTAINS_ALL:
            case TEXT_CONTAINS_PHRASE:
                comparisonString = "%s:(+\"%s\")";
                break;
            case IS_NULL:
                comparisonString = "*:* AND NOT %s:[* TO *]";
                break;
            case NOT_EQUALS:
                comparisonString = "NOT %s:";
                break;
            case TEXT_CONTAINS_ANY:
            case TEXT_CONTAINS_PREFIX:
            default:
                return null;
        }
        if (type != Comparisons.Type.IS_NULL && type != Comparisons.Type.TEXT_CONTAINS_PHRASE && type != Comparisons.Type.TEXT_CONTAINS_ALL) {
            if (comparison.getComparand() instanceof String) {
                comparisonString = comparisonString + "\"%s\"";
            } else {
                comparisonString = comparisonString + "%s";
            }
        }
        comparisonString = String.format(comparisonString, completeFieldName, filter.getComparison().getComparand());
        Comparisons.LuceneComparison luceneComparison = new Comparisons.LuceneComparison(comparisonString);
        if (filterSatisfiedMask != null) {
            filterSatisfiedMask.setSatisfied(true);
        }
        return new LuceneIndexQueryPlan(index.getName(), luceneComparison, false, groupingComparisons);
    }

    private LuceneIndexQueryPlan getScanForLuceneComponent(@Nonnull Index index, @Nonnull LuceneQueryComponent filter,
                                                           @Nullable FilterSatisfiedMask filterMask, final ScanComparisons groupingComparisons) {
        final Comparisons.LuceneComparison comparison;
        //TODO figure out how to take into account the parentField name here. Or maybe disallow this if its contained within a
        // oneOfThem. Not sure if thats even allowed via the metadata validation on the query at the start of the planner.
        for (String field : filter.getFields()) {
            if (!validateIndexField(index, field, false)) {
                return null;
            }
        }
        if (filter.getComparison() instanceof Comparisons.LuceneComparison) {
            comparison = (Comparisons.LuceneComparison)filter.getComparison();
        } else {
            return null;
        }
        if (filterMask != null) {
            filterMask.setSatisfied(true);
        }

        if (comparison.getType().equals(Comparisons.Type.FULL_TEXT_LUCENE_AUTO_COMPLETE)) {
            return new LuceneIndexQueryPlan(index.getName(), IndexScanType.BY_LUCENE_AUTO_COMPLETE, comparison, false, null, groupingComparisons);
        }
        // Set the scan type to spellcheck so the query plan can identify what to do in the executePlan function.
        if (comparison.getType().equals(Comparisons.Type.FULL_TEXT_LUCENE_SPELLCHECK)) {
            return new LuceneIndexQueryPlan(index.getName(), IndexScanType.BY_LUCENE_SPELLCHECK, comparison, false, null, groupingComparisons);
        }

        if (filter.multiFieldSearch) {
            return new LuceneIndexQueryPlan(index.getName(), IndexScanType.BY_LUCENE_FULL_TEXT, comparison, false, null, groupingComparisons);
        }
        return new LuceneIndexQueryPlan(index.getName(), IndexScanType.BY_LUCENE, comparison, false, null, groupingComparisons);
    }

    private LuceneIndexQueryPlan getScanForAndLucene(@Nonnull Index index, @Nullable String parentFieldName, @Nonnull AndComponent filter,
                                                     @Nullable FilterSatisfiedMask filterMask, final ScanComparisons groupingComparisons) {
        final Iterator<FilterSatisfiedMask> subFilterMasks = filterMask != null ? filterMask.getChildren().iterator() : null;
        final List<QueryComponent> filters = filter.getChildren();
        LuceneIndexQueryPlan combinedComparison = null;
        for (QueryComponent subFilter : filters) {
            final FilterSatisfiedMask childMask = subFilterMasks != null ? subFilterMasks.next() : null;
            LuceneIndexQueryPlan childComparison = getComparisonsForLuceneFilter(index, parentFieldName, subFilter, childMask, groupingComparisons);
            if (childComparison != null && childMask != null) {
                childMask.setSatisfied(true);
                combinedComparison = combinedComparison == null ? childComparison : LuceneIndexQueryPlan.merge(combinedComparison, childComparison, "AND");
            } else if (combinedComparison != null && childComparison == null) {
                combinedComparison = null;
                break;
            }
        }
        if (filterMask != null && filterMask.getUnsatisfiedFilters().isEmpty()) {
            filterMask.setSatisfied(true);
        }
        return combinedComparison;
    }

    // TODO Better implementation of nesting that actually takes into account
    //  positioning of the fields in relation to each other
    // This should use the multiField query parser. Very much a TODO
    private LuceneIndexQueryPlan getComparisonsForOneOfThem(@Nonnull Index index, @Nullable String parentFieldName, @Nonnull OneOfThemWithComponent filter,
                                                            @Nullable FilterSatisfiedMask mask, final ScanComparisons groupingComparisons) {
        String fieldName = filter.getFieldName();
        if (parentFieldName != null) {
            fieldName = parentFieldName + "_" + fieldName;
        }

        LuceneIndexQueryPlan comparison = getComparisonsForLuceneFilter(index, fieldName, filter.getChild(), (mask != null) ? mask.getChild(filter.getChild()) : null, groupingComparisons);
        if (comparison != null ) {
            if (mask != null) {
                mask.setSatisfied(true);
            }
            comparison.setCreatesDuplicates();
        }
        return comparison;
    }

    private LuceneIndexQueryPlan getScanForNestedField(@Nonnull Index index, String parentFieldName, NestedField filter, FilterSatisfiedMask mask, final ScanComparisons groupingComparisons) {
        String fieldName = filter.getFieldName();
        if (parentFieldName != null) {
            fieldName = parentFieldName + "_" + fieldName;
        }
        LuceneIndexQueryPlan comparison = getComparisonsForLuceneFilter(index, fieldName, filter.getChild(), (mask != null) ? mask.getChild(filter.getChild()) : null, groupingComparisons);
        if (comparison != null && mask != null) {
            mask.setSatisfied(true);
        }
        return comparison;
    }

    private LuceneIndexQueryPlan getScanForOrLucene(@Nonnull Index index, final String parentFieldName,
                                                    final OrComponent filter, final FilterSatisfiedMask filterMask, final ScanComparisons groupingComparisons) {
        final Iterator<FilterSatisfiedMask> subFilterMasks = filterMask != null ? filterMask.getChildren().iterator() : null;
        final List<QueryComponent> filters = filter.getChildren();
        LuceneIndexQueryPlan combinedComparison = null;
        for (QueryComponent subFilter : filters) {
            final FilterSatisfiedMask childMask = subFilterMasks != null ? subFilterMasks.next() : null;
            LuceneIndexQueryPlan childComparison = getComparisonsForLuceneFilter(index, parentFieldName, subFilter, childMask, groupingComparisons);
            if (childComparison != null && childMask != null) {
                childMask.setSatisfied(true);
                combinedComparison = combinedComparison == null ? childComparison : LuceneIndexQueryPlan.merge(childComparison, combinedComparison, "OR");
            }
        }
        if (filterMask != null && filterMask.getUnsatisfiedFilters().isEmpty()) {
            filterMask.setSatisfied(true);
        }
        return combinedComparison;
    }

    private LuceneIndexQueryPlan getComparisonsForLuceneFilter(@Nonnull Index index, @Nullable String parentFieldName, @Nonnull QueryComponent filter,
                                                               FilterSatisfiedMask filterMask, ScanComparisons groupingComparisons) {
        if (filter instanceof AndComponent) {
            return getScanForAndLucene(index, parentFieldName, (AndComponent) filter, filterMask, groupingComparisons);
        } else if (filter instanceof LuceneQueryComponent) {
            return getScanForLuceneComponent(index, (LuceneQueryComponent)filter, filterMask, groupingComparisons);
        } else if (filter instanceof OneOfThemWithComponent) {
            return getComparisonsForOneOfThem(index, parentFieldName, (OneOfThemWithComponent)filter, filterMask, groupingComparisons);
        } else if (filter instanceof FieldWithComparison) {
            return getScanForFieldWithComparison(index, parentFieldName, (FieldWithComparison) filter, filterMask, groupingComparisons);
        } else if (filter instanceof NestedField) {
            return getScanForNestedField(index, parentFieldName, (NestedField) filter, filterMask, groupingComparisons);
        } else if (filter instanceof OrComponent) {
            return getScanForOrLucene(index, parentFieldName, (OrComponent) filter, filterMask, groupingComparisons);
        }
        return null;
    }

    public boolean validateIndexField(@Nonnull Index index, @Nonnull String field, boolean complete) {
        for (RecordType recordType : getRecordMetaData().recordTypesForIndex(index)) {
            if (complete) {
                // For a filter made from components, we have the complete path of record fields and so the actual document field name.
                // TODO: This is inefficient to do for every comparison and doesn't really work if any document fields take their name from runtime values.
                final Map<String, LuceneIndexExpressions.DocumentFieldType> fields = LuceneIndexExpressions.getDocumentFieldTypes(index.getRootExpression(), recordType.getDescriptor());
                if (fields.containsKey(field)) {
                    return true;
                }
            } else {
                // Otherwise can only check that the field name given corresponds to some top-level branch to an indexed field.
                final List<List<String>> paths = LuceneIndexExpressions.getRecordFieldsPaths(index.getRootExpression(), recordType.getDescriptor());
                for (List<String> path : paths) {
                    if (path.get(0).equals(field)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected ScoredPlan planLucene(@Nonnull CandidateScan candidateScan,
                                    @Nonnull Index index, @Nonnull QueryComponent filter,
                                    @Nullable KeyExpression sort) {

        FilterSatisfiedMask filterMask = FilterSatisfiedMask.of(filter);


        KeyExpression rootExp = index.getRootExpression();
        ScanComparisons groupingComparisons;

        // Getting grouping information from the index key and query filter
        if (rootExp instanceof GroupingKeyExpression) {
            KeyExpression groupingKey = ((GroupingKeyExpression)rootExp).getGroupingSubKey();
            QueryToKeyMatcher.Match groupingMatch = new QueryToKeyMatcher(filter).matchesCoveringKey(groupingKey, filterMask);
            if (!groupingMatch.getType().equals((QueryToKeyMatcher.MatchType.EQUALITY))) {
                return null;
            }
            groupingComparisons = new ScanComparisons(groupingMatch.getEqualityComparisons(), Collections.emptySet());
        } else {
            groupingComparisons = null;
        }

        LuceneIndexQueryPlan lucenePlan = getComparisonsForLuceneFilter(index, null, filter, filterMask, groupingComparisons);
        if (lucenePlan == null) {
            return null;
        }

        RecordQueryPlan plan = lucenePlan;
        plan = addTypeFilterIfNeeded(candidateScan, plan, getPossibleTypes(index));
        if (filterMask.allSatisfied()) {
            filterMask.setSatisfied(true);
        }
        return new ScoredPlan(plan, filterMask.getUnsatisfiedFilters(), Collections.emptyList(),  11 - filterMask.getUnsatisfiedFilters().size(),
                lucenePlan.createsDuplicates(), null);
    }
}
