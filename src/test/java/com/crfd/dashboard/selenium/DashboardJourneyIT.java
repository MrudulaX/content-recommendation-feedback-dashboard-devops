package com.crfd.dashboard.selenium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selenium WebDriver suite for the five critical user journeys of the
 * Content Recommendation Feedback Dashboard.
 *
 * <pre>
 *   J1  Dashboard loads and renders summary indicators
 *   J2  Data / event entry publishes a feedback event
 *   J3  Searchable dashboard: free-text search + status facet
 *   J4  Status drill-down: row -> timeline + related alerts
 *   J5  Alert / exception view: acknowledge an open exception
 * </pre>
 *
 * <p>Run locally:</p>
 * <pre>
 *   mvn -P selenium verify -Dselenium.baseUrl=http://localhost:18080
 *   mvn -P selenium verify -Dselenium.browser=edge -Dselenium.headless=false
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CRFD critical user journeys (Selenium)")
class DashboardJourneyIT extends SeleniumTestSupport {

    // ================================================================== J1

    @Test
    @Order(1)
    @DisplayName("J1 - the dashboard loads and renders summary indicators, charts and the event table")
    void j1DashboardRendersSummaryIndicators() {
        open("/");

        assertThat(driver.getTitle()).contains("Content Recommendation Feedback Dashboard");

        // --- KPI strip is populated (not the '–' placeholder) ---
        await(d -> {
            String value = d.findElement(By.cssSelector("#kpi-total [data-value]")).getText();
            return !value.isBlank() && !"–".equals(value);
        });
        assertThat(text(By.cssSelector("#kpi-total [data-value]"))).matches("[\\d,]+");
        assertThat(find(By.cssSelector("#kpi-processed [data-value]")).getText()).isNotBlank();
        assertThat(find(By.cssSelector("#kpi-latency [data-value]")).getText()).isNotBlank();
        assertThat(find(By.cssSelector("#kpi-open-alerts [data-value]")).getText()).isNotBlank();

        // --- the deep health pill resolved to a real status ---
        await(d -> !"UNKNOWN".equals(d.findElement(By.id("health-pill")).getAttribute("data-status")));
        assertThat(attribute(By.id("health-pill"), "data-status")).isIn("UP", "DEGRADED");

        // --- the environment badge is bound from configuration ---
        assertThat(text(By.id("env-badge"))).startsWith("env:");

        // --- charts rendered ---
        assertThat(isDisplayed(By.cssSelector("#timeseries-chart svg"))).isTrue();
        assertThat(isDisplayed(By.cssSelector("#status-donut svg"))).isTrue();
        assertThat(all(By.cssSelector("#dist-source .bar-row"))).isNotEmpty();

        // --- the searchable table has rows (seeded history) ---
        waitForAtLeast(By.cssSelector("#events-body tr[data-event-id]"), 1);
        assertThat(text(By.id("pagination-info"))).contains("of");
    }

    // ================================================================== J2

    @Test
    @Order(2)
    @DisplayName("J2 - a submitted feedback event is accepted and becomes searchable")
    void j2EventEntryPublishesEvent() {
        String itemId = uniqueItemId("ITM-SELJ2");

        open("/");
        click(By.id("btn-add-event"));
        visible(By.id("event-modal"));
        type(By.id("field-itemId"), itemId);
        type(By.id("field-itemTitle"), "Selenium journey 2 item");
        select(By.id("field-eventType"), "CLICK");
        select(By.id("field-source"), "COLLABORATIVE_FILTER");
        select(By.id("field-surface"), "PRODUCT_PAGE");
        type(By.id("field-score"), "0.93");
        click(By.id("btn-submit-event"));

        // --- the form reports acceptance with the generated business key ---
        await(d -> d.findElement(By.id("event-form-message")).getText().contains("Accepted"));
        assertThat(text(By.id("event-form-message"))).contains("EVT-");

        // --- the modal closes and the dashboard re-queries for the new item ---
        await(ExpectedConditions.invisibilityOfElementLocated(By.id("event-modal")));

        // The dashboard re-queries after the write, so wait for the new row to appear
        // rather than assuming the table has already re-rendered - and tolerate the
        // re-render itself, which detaches any element handle captured too early.
        List<String> itemCells = await(d -> {
            List<String> cells = d.findElements(By.cssSelector("#events-body tr[data-event-id] td:nth-child(4) div"))
                    .stream().map(WebElement::getText).collect(Collectors.toList());
            return cells.contains(itemId) ? cells : null;
        });
        assertThat(itemCells).contains(itemId);

        String appliedSearch = await(d -> d.findElement(By.id("search-input")).getAttribute("value"));
        assertThat(appliedSearch).isEqualTo(itemId);
    }

    // ================================================================== J3

    @Test
    @Order(3)
    @DisplayName("J3 - free-text search combined with the status facet returns only matching events")
    void j3SearchAndStatusFacet() {
        String failedItem = uniqueItemId("ITM-SELJ3FAIL");
        String processedItem = uniqueItemId("ITM-SELJ3OK");

        // A served impression without a confidence score is rejected by the pipeline -> FAILED.
        submitEvent(failedItem, "IMPRESSION", "");
        // A well formed impression is processed normally -> PROCESSED.
        submitEvent(processedItem, "IMPRESSION", "0.86");

        // Narrow to both events by a shared prefix, then let the status facet do the work.
        open("/");
        type(By.id("search-input"), "ITM-SELJ3");
        select(By.id("filter-status"), "FAILED");
        click(By.id("btn-apply-filters"));

        // The pipeline is asynchronous: wait until the FAILED event surfaces in the filtered view.
        await(d -> d.findElements(By.cssSelector("#events-body tr[data-event-id]")).stream()
                .anyMatch(row -> row.findElement(By.cssSelector("td:nth-child(4) div")).getText().contains("ITM-SELJ3FAIL")));

        List<String> statuses = await(d -> {
            List<String> collected = d
                    .findElements(By.cssSelector("#events-body tr[data-event-id] td:nth-child(8) .chip"))
                    .stream().map(WebElement::getText).collect(Collectors.toList());
            return collected.isEmpty() ? null : collected;
        });
        assertThat(statuses).isNotEmpty().allMatch("FAILED"::equals);

        // Paging metadata must reflect the filtered result set, not the whole table.
        assertThat(text(By.id("pagination-info"))).contains("of").doesNotContain("of 0");
    }

    // ================================================================== J4

    @Test
    @Order(4)
    @DisplayName("J4 - drilling into an event shows its state, audit timeline and related alerts")
    void j4StatusDrillDown() {
        String itemId = uniqueItemId("ITM-SELJ4");
        submitEvent(itemId, "IMPRESSION", "0.04"); // low confidence -> LOW_CONFIDENCE alert

        open("/");
        type(By.id("search-input"), itemId);
        click(By.id("btn-apply-filters"));
        waitForAtLeast(By.cssSelector("#events-body tr[data-event-id]"), 1);

        click(By.cssSelector("#events-body tr[data-event-id] td:nth-child(2)"));
        visible(By.id("detail-drawer"));

        assertThat(text(By.id("detail-event-id"))).startsWith("EVT-");

        // The pipeline completes asynchronously: reopen the drawer until the event is terminal.
        await(d -> {
            if (d.findElements(By.cssSelector("#detail-timeline li")).size() >= 3) {
                return true;
            }
            // The pipeline completes asynchronously, so reopen the drawer to refresh the
            // timeline. Both lookups are defensive: the underlying table re-renders.
            d.findElements(By.id("detail-close")).stream().findFirst().ifPresent(WebElement::click);
            List<WebElement> rows = d.findElements(By.cssSelector("#events-body tr[data-event-id]"));
            if (!rows.isEmpty()) {
                rows.get(0).click();
            }
            return false;
        });

        // --- status + timeline ---
        assertThat(all(By.cssSelector("#detail-status-badges .chip"))).isNotEmpty();
        List<WebElement> timeline = all(By.cssSelector("#detail-timeline li"));
        assertThat(timeline).hasSizeGreaterThanOrEqualTo(3);
        assertThat(timeline.get(0).getText()).contains("RECEIVED");
        assertThat(timeline.get(1).getText()).contains("PROCESSING");

        // --- related alerts (the low confidence exception raised by the pipeline) ---
        // An event can legitimately raise more than one exception: the confidence and
        // latency rules are independent, and the latency the simulator records is
        // random. Assert that the low-confidence alert is present, rather than that
        // it happens to be the first row in the drawer.
        waitForAtLeast(By.cssSelector("#detail-alerts .alert-item"), 1);
        assertThat(all(By.cssSelector("#detail-alerts .alert-item .alert-code")))
                .extracting(WebElement::getText)
                .contains("LOW_CONFIDENCE");

        // --- key/value details are rendered ---
        assertThat(text(By.id("detail-fields"))).contains("Correlation id").contains("Latency");

        click(By.id("detail-close"));
        await(ExpectedConditions.invisibilityOfElementLocated(By.id("detail-drawer")));
        assertThat(isDisplayed(By.id("detail-drawer"))).isFalse();
    }

    // ================================================================== J5

    @Test
    @Order(5)
    @DisplayName("J5 - the exception view lists open alerts and an operator can acknowledge one")
    void j5AlertExceptionViewAcknowledge() {
        String itemId = uniqueItemId("ITM-SELJ5");
        submitEvent(itemId, "IMPRESSION", "0.02"); // guaranteed LOW_CONFIDENCE alert

        open("/");
        select(By.id("alert-status-filter"), "OPEN");
        waitForAtLeast(By.cssSelector("#alerts-list .alert-item"), 1);

        await(d -> !"Loading…".equals(d.findElement(By.id("alerts-list")).getText()));

        // Locate this journey's low-confidence exception instead of "whatever is
        // first": a CRITICAL latency alert raised by an earlier journey can sort
        // above it. Every read goes through await() so that a scheduled re-render
        // cannot detach an element mid-assertion.
        String alertRef = await(d -> d.findElements(By.cssSelector("#alerts-list .alert-item")).stream()
                .filter(item -> "LOW_CONFIDENCE".equals(item.findElement(By.cssSelector(".alert-code")).getText()))
                .map(item -> item.getAttribute("data-alert-ref"))
                .findFirst().orElse(null));
        assertThat(alertRef).isNotBlank();

        assertThat(fieldOf(alertRef, ".alert-code")).isEqualTo("LOW_CONFIDENCE");
        assertThat(fieldOf(alertRef, ".chip")).isEqualTo("OPEN");
        assertThat(fieldOf(alertRef, ".alert-ref")).isEqualTo(alertRef);

        // --- acknowledge, targeting this exact alert rather than "whatever is first" ---
        find(By.cssSelector("#alerts-list .alert-item[data-alert-ref='" + alertRef + "'] [data-action='ack']"))
                .click();

        // --- the exception is no longer part of the OPEN work queue ---
        select(By.id("alert-status-filter"), "ACKNOWLEDGED");
        await(d -> d.findElements(By.cssSelector("#alerts-list .alert-item")).stream()
                .anyMatch(item -> alertRef.equals(item.getAttribute("data-alert-ref"))));

        assertThat(fieldOf(alertRef, ".chip")).isEqualTo("ACKNOWLEDGED");
        String acknowledgedRow = await(d -> d.findElements(By.cssSelector(
                        "#alerts-list .alert-item[data-alert-ref='" + alertRef + "']"))
                .stream().map(WebElement::getText).findFirst().orElse(null));
        assertThat(acknowledgedRow).contains("duty-engineer");
    }

    // ================================================================== helpers

    /**
     * Reads one child field of a specific alert, addressed by its business key.
     * Routed through {@code await} so a concurrent list re-render is retried rather
     * than failing the journey with a stale-element error.
     */
    private String fieldOf(String alertRef, String childSelector) {
        return await(d -> d.findElements(By.cssSelector(
                        "#alerts-list .alert-item[data-alert-ref='" + alertRef + "']"))
                .stream()
                .map(item -> item.findElement(By.cssSelector(childSelector)).getText())
                .findFirst()
                .orElse(null));
    }

    /** Completes the event-entry modal and waits until the dashboard acknowledges the write. */
    private void submitEvent(String itemId, String eventType, String score) {
        open("/");
        click(By.id("btn-add-event"));
        visible(By.id("event-modal"));
        type(By.id("field-itemId"), itemId);
        type(By.id("field-itemTitle"), "Selenium item " + itemId);
        select(By.id("field-eventType"), eventType);
        type(By.id("field-score"), score);
        click(By.id("btn-submit-event"));

        await(d -> d.findElement(By.id("event-form-message")).getText().contains("Accepted"));
        await(ExpectedConditions.invisibilityOfElementLocated(By.id("event-modal")));
    }
}
