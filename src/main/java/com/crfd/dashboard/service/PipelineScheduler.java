package com.crfd.dashboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decides <em>when</em> an ingested event is handed to the enrichment pipeline.
 *
 * <p>Two concerns are deliberately kept out of {@link EventProcessingService}:
 * <ol>
 *   <li><b>Transaction boundary</b> - the pipeline must not start before the ingest
 *       transaction has committed, otherwise the worker thread cannot see the row it has
 *       to process. When a transaction is active the dispatch is deferred to
 *       {@code afterCommit}.</li>
 *   <li><b>Execution mode</b> - asynchronous by default on the bounded
 *       {@code eventExecutor}, synchronous when {@code crfd.processing.async-enabled=false}.
 *       The synchronous mode is what makes the pipeline deterministic in tests and in
 *       single-threaded batch/replay runs.</li>
 * </ol>
 */
@Component
public class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    private final EventProcessingService processingService;
    private final TaskExecutor executor;
    private final boolean asyncEnabled;

    public PipelineScheduler(EventProcessingService processingService,
                             @Qualifier("eventExecutor") TaskExecutor executor,
                             @Value("${crfd.processing.async-enabled:true}") boolean asyncEnabled) {
        this.processingService = processingService;
        this.executor = executor;
        this.asyncEnabled = asyncEnabled;
    }

    /** Submit an event for enrichment, honouring the surrounding transaction. */
    public void schedule(String eventId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(eventId);
                }
            });
        } else {
            dispatch(eventId);
        }
    }

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    private void dispatch(String eventId) {
        if (asyncEnabled) {
            executor.execute(() -> run(eventId));
        } else {
            run(eventId);
        }
    }

    private void run(String eventId) {
        try {
            processingService.process(eventId);
        } catch (RuntimeException ex) {
            // The pipeline must never take the ingestion path down with it.
            log.error("Enrichment pipeline failed for event {}: {}", eventId, ex.getMessage(), ex);
        }
    }
}
