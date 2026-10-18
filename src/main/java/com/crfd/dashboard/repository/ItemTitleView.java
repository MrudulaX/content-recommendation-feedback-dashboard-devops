package com.crfd.dashboard.repository;

/** Read-only projection: an item id and its display title. */
public interface ItemTitleView {
    String getItemId();

    String getItemTitle();
}
