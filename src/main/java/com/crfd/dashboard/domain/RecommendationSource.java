package com.crfd.dashboard.domain;

/** Which recommender produced the served recommendation. */
public enum RecommendationSource {
    COLLABORATIVE_FILTER,
    CONTENT_BASED,
    TRENDING,
    EDITORIAL,
    HYBRID
}
