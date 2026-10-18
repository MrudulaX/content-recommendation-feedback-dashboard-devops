package com.crfd.dashboard.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared WebDriver lifecycle for the CRFD Selenium suite.
 *
 * <p>Supported execution modes, selected by environment so that the same suite runs
 * locally (Maven) and in the Jenkins container without code changes:</p>
 *
 * <ul>
 *   <li><b>Local</b> - a local browser, driven headless by default.
 *       {@code -Dselenium.browser=auto|chrome|edge|firefox}</li>
 *   <li><b>Remote / CI</b> - set {@code SELENIUM_REMOTE_URL}
 *       (e.g. {@code http://selenium-chrome:4444/wd/hub}) to drive a
 *       {@code selenium/standalone-chrome} container.</li>
 * </ul>
 *
 * <p>Every failure writes a PNG screenshot and the page source into
 * {@code crfd.screenshots.dir} (default {@code target/selenium-screenshots}) and logs the
 * absolute path, which is what the Jenkins job archives as failure evidence.</p>
 */
public abstract class SeleniumTestSupport {

    private static final Logger log = LoggerFactory.getLogger(SeleniumTestSupport.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    protected static WebDriver driver;
    protected static WebDriverWait wait;
    protected static String baseUrl;

    /** Screenshots captured during this run - reported in @AfterAll for the build log. */
    private static final List<Path> captures = new ArrayList<>();

    @RegisterExtension
    static final TestWatcher SCREENSHOT_ON_FAILURE = new ScreenshotOnFailure();

    @BeforeAll
    static void startDriver() {
        baseUrl = System.getProperty("selenium.baseUrl",
                System.getenv().getOrDefault("SELENIUM_BASE_URL", "http://localhost:8080"));
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        boolean headless = Boolean.parseBoolean(System.getProperty("selenium.headless", "true"));
        driver = createDriver(headless);
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20));
        driver.manage().window().setSize(new Dimension(1600, 1000));
        wait = new WebDriverWait(driver, Duration.ofSeconds(25), Duration.ofMillis(250));
        log.info("Selenium suite driving {} (headless={}, remote={})",
                baseUrl, headless, System.getenv("SELENIUM_REMOTE_URL") != null);
    }

    @AfterAll
    static void stopDriver() {
        if (!captures.isEmpty()) {
            log.error("{} failure screenshot(s) captured:", captures.size());
            captures.forEach(path -> log.error("  {}", path.toAbsolutePath()));
        }
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }

    // ------------------------------------------------------------------ driver factory

    private static WebDriver createDriver(boolean headless) {
        String remote = System.getenv("SELENIUM_REMOTE_URL");
        String requested = System.getProperty("selenium.browser", "auto").toLowerCase();

        if (remote != null && !remote.isBlank()) {
            log.info("Using remote WebDriver at {}", remote);
            return new RemoteWebDriver(toUrl(remote), remoteOptions(requested, headless));
        }

        List<String> candidates = "auto".equals(requested)
                ? List.of("chrome", "edge", "firefox")
                : List.of(requested);

        RuntimeException last = null;
        for (String browser : candidates) {
            try {
                WebDriver created = localDriver(browser, headless);
                log.info("Local WebDriver ready: {}", browser);
                return created;
            } catch (RuntimeException ex) {
                log.warn("Could not start {} locally: {}", browser, ex.getMessage());
                last = ex;
            }
        }
        throw new IllegalStateException(
                "No usable local browser found. Install Chrome/Edge/Firefox, or set SELENIUM_REMOTE_URL "
                        + "to a selenium/standalone-chrome container.", last);
    }

    private static WebDriver localDriver(String browser, boolean headless) {
        switch (browser) {
            case "chrome" -> {
                ChromeOptions options = chromeOptions(headless);
                try {
                    return new ChromeDriver(options);
                } catch (RuntimeException first) {
                    WebDriverManager.chromedriver().setup();
                    return new ChromeDriver(options);
                }
            }
            case "edge" -> {
                EdgeOptions options = edgeOptions(headless);
                try {
                    return new EdgeDriver(options);
                } catch (RuntimeException first) {
                    WebDriverManager.edgedriver().setup();
                    return new EdgeDriver(options);
                }
            }
            case "firefox" -> {
                FirefoxOptions options = firefoxOptions(headless);
                try {
                    return new FirefoxDriver(options);
                } catch (RuntimeException first) {
                    WebDriverManager.firefoxdriver().setup();
                    return new FirefoxDriver(options);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported browser: " + browser);
        }
    }

    private static org.openqa.selenium.Capabilities remoteOptions(String browser, boolean headless) {
        return switch (browser) {
            case "edge" -> edgeOptions(headless);
            case "firefox" -> firefoxOptions(headless);
            default -> chromeOptions(headless);
        };
    }

    private static ChromeOptions chromeOptions(boolean headless) {
        ChromeOptions options = new ChromeOptions();
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.NONE);
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--no-proxy-server",
                "--window-size=1600,1000",
                "--remote-allow-origins=*",
                "--disable-extensions",
                "--disable-background-networking",
                "--disable-sync",
                "--no-first-run",
                "--disable-default-apps",
                "--mute-audio");
        return options;
    }

    private static EdgeOptions edgeOptions(boolean headless) {
        EdgeOptions options = new EdgeOptions();
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.NONE);
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
                "--no-proxy-server", "--window-size=1600,1000");
        return options;
    }

    private static FirefoxOptions firefoxOptions(boolean headless) {
        FirefoxOptions options = new FirefoxOptions();
        if (headless) {
            options.addArguments("-headless");
        }
        options.setPageLoadStrategy(org.openqa.selenium.PageLoadStrategy.NONE);
        options.addArguments("--width=1600", "--height=1000");
        return options;
    }

    private static java.net.URL toUrl(String value) {
        try {
            return URI.create(value).toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SELENIUM_REMOTE_URL: " + value, e);
        }
    }

    // ------------------------------------------------------------------ navigation helpers

    protected void open(String path) {
        driver.get(baseUrl + path);
        // The suite uses PageLoadStrategy.NONE because Chrome's renderer can fail to signal
        // "load complete" for an app that keeps a polling timer alive (the dashboard
        // auto-refreshes every 15s). Readiness is asserted explicitly instead.
        await(d -> "complete".equals(((org.openqa.selenium.JavascriptExecutor) d)
                        .executeScript("return document.readyState"))
                && !d.findElements(By.id("kpi-strip")).isEmpty());
    }

    protected void openAndWaitFor(String path, By marker) {
        driver.get(baseUrl + path);
        visible(marker);
    }

    protected WebElement find(By by) {
        return wait.until(ExpectedConditions.presenceOfElementLocated(by));
    }

    protected WebElement visible(By by) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(by));
    }

    protected void click(By by) {
        wait.until(ExpectedConditions.elementToBeClickable(by)).click();
    }

    protected void type(By by, String text) {
        WebElement element = visible(by);
        element.clear();
        if (text != null && !text.isEmpty()) {
            element.sendKeys(text);
        }
    }

    protected void select(By by, String value) {
        new Select(visible(by)).selectByValue(value);
    }

    protected String text(By by) {
        return visible(by).getText();
    }

    protected String textOf(By by) {
        WebElement element = find(by);
        return element.getText();
    }

    protected String attribute(By by, String name) {
        return find(by).getAttribute(name);
    }

    protected List<WebElement> all(By by) {
        return driver.findElements(by);
    }

    protected boolean isDisplayed(By by) {
        List<WebElement> found = driver.findElements(by);
        return !found.isEmpty() && found.get(0).isDisplayed();
    }

    protected <T> T await(Function<WebDriver, T> condition) {
        // The dashboard re-renders its tables on every poll (it auto-refreshes every 15s,
        // and again after each mutation). An element captured during one poll can therefore
        // be detached from the DOM by the time the condition inspects it. Staleness here
        // means "ask again", not "the application is broken", so both it and a briefly
        // missing element are ignored and re-evaluated until the wait times out for real.
        return wait.ignoring(StaleElementReferenceException.class)
                .ignoring(NoSuchElementException.class)
                .until(condition);
    }

    /** Waits for a CSS selector to match at least {code count} elements. */
    protected void waitForAtLeast(By by, int count) {
        wait.until(d -> d.findElements(by).size() >= count);
    }

    protected static String uniqueItemId(String prefix) {
        return prefix + "-" + System.currentTimeMillis() % 1_000_000_000L;
    }

    // ------------------------------------------------------------------ failure capture

    private static class ScreenshotOnFailure implements TestWatcher {

        @Override
        public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context, Throwable cause) {
            String name = context.getDisplayName().replaceAll("[^A-Za-z0-9._-]", "_");
            capture(name, cause);
        }

        private void capture(String name, Throwable cause) {
            if (driver == null) {
                return;
            }
            try {
                Path dir = Path.of(System.getProperty("crfd.screenshots.dir", "target/selenium-screenshots"));
                Files.createDirectories(dir);
                String stamp = STAMP.format(LocalDateTime.now());

                File png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                Path image = dir.resolve(name + "-" + stamp + ".png");
                Files.copy(png.toPath(), image, StandardCopyOption.REPLACE_EXISTING);

                Path html = dir.resolve(name + "-" + stamp + ".html");
                Files.writeString(html, driver.getPageSource());

                Path diag = dir.resolve(name + "-" + stamp + ".txt");
                Files.writeString(diag, "url=" + baseUrl + "\ncause=" + cause + "\n");

                captures.add(image);
                log.error("FAILURE SCREENSHOT saved to {}", image.toAbsolutePath());
                log.error("Page source saved to {}", html.toAbsolutePath());
            } catch (Exception ex) {
                log.warn("Could not capture the failure screenshot: {}", ex.getMessage());
            }
        }
    }
}
