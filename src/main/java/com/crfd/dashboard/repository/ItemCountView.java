package com.crfd.dashboard.repository;

/** Read-only projection: an item id and how many events referenced it. */
public interface ItemCountView {
    String getItemId();

    long getTotal();
}
