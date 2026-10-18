package com.crfd.dashboard.domain;

/** The user action the recommender produced a reaction to. */
public enum EventType {
    /** Recommendation card rendered to the user. */
    IMPRESSION,
    /** User opened the recommended content. */
    CLICK,
    /** User added the recommended item to the basket. */
    ADD_TO_CART,
    /** User purchased the recommended item. */
    PURCHASE,
    /** User explicitly dismissed / hid the recommendation. */
    DISMISS,
    /** User rated the recommendation quality (1-5). */
    RATING
}
