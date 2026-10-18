package com.crfd.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised, environment-overridable configuration for the dashboard.
 *
 * <p>Everything that an operator may want to tune per environment (SLA thresholds, the
 * metrics window, whether synthetic traffic is allowed) lives here and is bound from
 * {@code crfd.*} in {@code application.yml} / environment variables. This is also the
 * parameterisation target used by the Jenkins pipeline and the Ansible deployment.</p>
 */
@Component
@ConfigurationProperties(prefix = "crfd")
public class CrfdProperties {

    /** Logical environment name shown on the dashboard header, e.g. local / staging / prod. */
    private String environment = "local";

    private final Simulation simulation = new Simulation();
    private final Processing processing = new Processing();
    private final Thresholds thresholds = new Thresholds();
    private final Metrics metrics = new Metrics();

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public Simulation getSimulation() { return simulation; }
    public Processing getProcessing() { return processing; }
    public Thresholds getThresholds() { return thresholds; }
    public Metrics getMetrics() { return metrics; }

    /** Synthetic traffic generation - used for demos and for the local/dev profile only. */
    public static class Simulation {
        private boolean enabled = true;
        private boolean seedOnStartup = true;
        private int seedEventCount = 180;
        private int seedHoursBack = 48;
        private int maxBatch = 500;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isSeedOnStartup() { return seedOnStartup; }
        public void setSeedOnStartup(boolean seedOnStartup) { this.seedOnStartup = seedOnStartup; }

        public int getSeedEventCount() { return seedEventCount; }
        public void setSeedEventCount(int seedEventCount) { this.seedEventCount = seedEventCount; }

        public int getSeedHoursBack() { return seedHoursBack; }
        public void setSeedHoursBack(int seedHoursBack) { this.seedHoursBack = seedHoursBack; }

        public int getMaxBatch() { return maxBatch; }
        public void setMaxBatch(int maxBatch) { this.maxBatch = maxBatch; }
    }

    /** Model of the (simulated) feedback enrichment pipeline. */
    public static class Processing {
        private long baseLatencyMs = 22;
        private long jitterMs = 160;
        private double spikeProbability = 0.06;
        private long spikeLatencyMs = 1350;
        private boolean asyncEnabled = true;

        public long getBaseLatencyMs() { return baseLatencyMs; }
        public void setBaseLatencyMs(long baseLatencyMs) { this.baseLatencyMs = baseLatencyMs; }

        public long getJitterMs() { return jitterMs; }
        public void setJitterMs(long jitterMs) { this.jitterMs = jitterMs; }

        public double getSpikeProbability() { return spikeProbability; }
        public void setSpikeProbability(double spikeProbability) { this.spikeProbability = spikeProbability; }

        public long getSpikeLatencyMs() { return spikeLatencyMs; }
        public void setSpikeLatencyMs(long spikeLatencyMs) { this.spikeLatencyMs = spikeLatencyMs; }

        public boolean isAsyncEnabled() { return asyncEnabled; }
        public void setAsyncEnabled(boolean asyncEnabled) { this.asyncEnabled = asyncEnabled; }
    }

    /** Alerting / SLA thresholds. Changing these changes what the exception view shows. */
    public static class Thresholds {
        private double lowConfidence = 0.30;
        private long highLatencyMs = 500;
        private long criticalLatencyMs = 1500;
        private long staleEventHours = 24;
        private int dismissRateThreshold = 3;
        private long dismissWindowMinutes = 60;
        private int lowRatingThreshold = 2;
        private double minimumSuccessRate = 0.95;

        public double getLowConfidence() { return lowConfidence; }
        public void setLowConfidence(double lowConfidence) { this.lowConfidence = lowConfidence; }

        public long getHighLatencyMs() { return highLatencyMs; }
        public void setHighLatencyMs(long highLatencyMs) { this.highLatencyMs = highLatencyMs; }

        public long getCriticalLatencyMs() { return criticalLatencyMs; }
        public void setCriticalLatencyMs(long criticalLatencyMs) { this.criticalLatencyMs = criticalLatencyMs; }

        public long getStaleEventHours() { return staleEventHours; }
        public void setStaleEventHours(long staleEventHours) { this.staleEventHours = staleEventHours; }

        public int getDismissRateThreshold() { return dismissRateThreshold; }
        public void setDismissRateThreshold(int dismissRateThreshold) { this.dismissRateThreshold = dismissRateThreshold; }

        public long getDismissWindowMinutes() { return dismissWindowMinutes; }
        public void setDismissWindowMinutes(long dismissWindowMinutes) { this.dismissWindowMinutes = dismissWindowMinutes; }

        public int getLowRatingThreshold() { return lowRatingThreshold; }
        public void setLowRatingThreshold(int lowRatingThreshold) { this.lowRatingThreshold = lowRatingThreshold; }

        public double getMinimumSuccessRate() { return minimumSuccessRate; }
        public void setMinimumSuccessRate(double minimumSuccessRate) { this.minimumSuccessRate = minimumSuccessRate; }
    }

    /** Dashboard aggregation settings. */
    public static class Metrics {
        private long windowHours = 24;
        private int topItems = 5;
        private String defaultGranularity = "hour";

        public long getWindowHours() { return windowHours; }
        public void setWindowHours(long windowHours) { this.windowHours = windowHours; }

        public int getTopItems() { return topItems; }
        public void setTopItems(int topItems) { this.topItems = topItems; }

        public String getDefaultGranularity() { return defaultGranularity; }
        public void setDefaultGranularity(String defaultGranularity) { this.defaultGranularity = defaultGranularity; }
    }
}
