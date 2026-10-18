package com.crfd.dashboard.config;

import com.crfd.dashboard.repository.RecommendationEventRepository;
import com.crfd.dashboard.service.TrafficSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the local/dev environment with a realistic 48-hour history the first time the
 * application starts against an empty database, so the dashboard is demonstrable
 * immediately. Idempotent: it refuses to run when events already exist, which means it is
 * safe to leave in the released artefact (and disabled per environment via
 * {@code crfd.simulation.seed-on-startup=false}).
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final RecommendationEventRepository events;
    private final TrafficSimulator simulator;
    private final CrfdProperties properties;

    public DataSeeder(RecommendationEventRepository events, TrafficSimulator simulator, CrfdProperties properties) {
        this.events = events;
        this.simulator = simulator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        var simulation = properties.getSimulation();
        if (!simulation.isEnabled() || !simulation.isSeedOnStartup()) {
            log.info("Startup seeding disabled (crfd.simulation.enabled={}, seed-on-startup={})",
                    simulation.isEnabled(), simulation.isSeedOnStartup());
            return;
        }
        long existing = events.count();
        if (existing > 0) {
            log.info("Database already holds {} events - skipping startup seed", existing);
            return;
        }
        int created = simulator.seedHistory(simulation.getSeedEventCount(), simulation.getSeedHoursBack());
        log.info("Seeded {} backdated feedback events over the last {} hours",
                created, simulation.getSeedHoursBack());
    }
}
