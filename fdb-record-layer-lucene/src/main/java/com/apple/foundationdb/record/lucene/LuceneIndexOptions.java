/*
 * LuceneIndexOptions.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2022 Apple Inc. and the FoundationDB project authors
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

import com.apple.foundationdb.annotation.API;

/**
 * Options for use with Lucene indexes.
 */
@API(API.Status.EXPERIMENTAL)
public class LuceneIndexOptions {
    /**
     * Whether indexed for auto complete search.
     */
    public static final String AUTO_COMPLETE_ENABLED = "autoCompleteEnabled";
    /**
     * The type of auto complete blender to transform the weight after search to take into account the position of the searched term into the indexed text.
     * @deprecated this option is ignored as the blender suggester is no longer used by auto-complete queries
     */
    @Deprecated
    public static final String AUTO_COMPLETE_BLENDER_TYPE = "autoCompleteBlenderType";
    /**
     * The number factor to multiply the number of searched elements for auto complete blender.
     * @deprecated this option is ignored as the blender suggester is no longer used by auto-complete queries
     */
    @Deprecated
    public static final String AUTO_COMPLETE_BLENDER_NUM_FACTOR = "autoCompleteBlenderNumFactor";
    /**
     * The minimum number of leading characters before prefix query is used for auto complete.
     */
    public static final String AUTO_COMPLETE_MIN_PREFIX_SIZE = "autoCompleteMinPrefixSize";
    /**
     * The exponent to use for auto complete when the blender type is POSITION_EXPONENTIAL_RECIPROCAL.
     * @deprecated this option is ignored as the blender suggester is no longer used by auto-complete queries
     */
    @Deprecated
    public static final String AUTO_COMPLETE_BLENDER_EXPONENT = "autoCompleteBlenderExponent";
    /**
     * Whether a Lucene's EdgeNGramTokenFilter or a regular NGramTokenFilter to use for the ngram analyzer.
     */
    public static final String NGRAM_TOKEN_EDGES_ONLY = "ngramTokenEdgesOnly";
    /**
     * The name of the Lucene analyzer to use for full-text search.
     */
    public static final String LUCENE_ANALYZER_NAME_OPTION = "luceneAnalyzerName";
    /**
     * The override mapping from fields to Lucene analyzers, if they want to use different analyzer than the default one.
     * The format of the value should be "fieldName1/analyzerName1/fieldName2/analyzerName2...".
     */
    public static final String LUCENE_ANALYZER_NAME_PER_FIELD_OPTION = "luceneAnalyzerNamePerField";
    /**
     * The name of the Lucene analyzer to use for auto-complete query.
     */
    public static final String AUTO_COMPLETE_ANALYZER_NAME_OPTION = "autoCompleteAnalyzerName";
    /**
     * The override mapping from fields to Lucene analyzers for auto-complete query, if they want to use different analyzers than the default one.
     * The format of the value should be "fieldName1/analyzerName1/fieldName2/analyzerName2...", with keys and values splitted by "/".
     */
    public static final String AUTO_COMPLETE_ANALYZER_NAME_PER_FIELD_OPTION = "autoCompleteAnalyzerNamePerField";
    /**
     * The name of the synonym set to use in Lucene.
     */
    public static final String TEXT_SYNONYM_SET_NAME_OPTION = "textSynonymSetName";

    /**
     * A list of fields to be excluded for auto-complete query.
     * The format of the value should be "fieldName1/fieldName2/...", with field names splitted by "/".
     */
    public static final String AUTO_COMPLETE_EXCLUDED_FIELDS = "autoCompleteExcludedFields";

    private LuceneIndexOptions() {
    }
}
