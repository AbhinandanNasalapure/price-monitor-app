package com.pricemonitor.scrape;

import com.pricemonitor.product.Availability;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PriceReader {
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w])(?:\\d{1,3}(?:[,. ]\\d{3})+|\\d+)(?:[.,]\\d{1,2})?(?![\\w])");
    private static final List<String> META_PRICE_SELECTORS = List.of(
            "meta[property='product:price:amount']",
            "meta[property='og:price:amount']",
            "meta[itemprop='price']",
            "[itemprop='price'][content]"
    );
    private static final List<String> AMAZON_PRICE_SELECTORS = List.of(
            "#apex_offerDisplay_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#apex_offerDisplay_single_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#corePrice_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#corePriceDisplay_desktop_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#apex_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#priceblock_dealprice",
            "#priceblock_ourprice",
            "#priceblock_saleprice",
            "#price_inside_buybox",
            "#newBuyBoxPrice"
    );
    // Flipkart uses short-lived class names, so keep both current and older
    // product-page price containers. Deliberately avoid scanning arbitrary body
    // numbers on Flipkart (that can capture EMI amounts, fees, or specifications).
    private static final List<String> FLIPKART_PRICE_SELECTORS = List.of(
            "div.Nx9bqj.CxhGGd",
            "div.Nx9bqj",
            "div._30jeq3._16Jk6d",
            "div._30jeq3",
            "[itemprop='price'][content]",
            "[itemprop='price']"
    );
    private static final List<String> AVAILABILITY_SELECTORS = List.of(
            "#availability",
            "#outOfStock",
            "[data-testid*='availability']",
            "[data-testid*='stock']",
            "[class*='out-of-stock']",
            "[class*='outOfStock']",
            "[class*='soldOut']",
            "[class*='availability']",
            "[class*='Availability']"
    );

    private final ObjectMapper objectMapper;
    private final int timeoutMs;

    public PriceReader(ObjectMapper objectMapper, @Value("${monitor.request-timeout-ms:20000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
    }

    public PriceReading read(String productUrl, String customSelector) {
        if (isAmazonHost(productUrl) && !isAmazonProductPage(productUrl)) {
            throw new IllegalStateException("Use the direct Amazon product page URL (it must contain /dp/ and the product ASIN), not the Amazon home or search page.");
        }
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.setDefaultNavigationTimeout(timeoutMs);
                page.route("**/*", route -> guardRequest(route));
                Response response = page.navigate(productUrl, new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                if (response != null && response.status() >= 400) {
                    throw new IllegalStateException("Product page returned HTTP " + response.status() + ".");
                }

                boolean amazon = isAmazonHost(productUrl);
                boolean flipkart = isFlipkartHost(productUrl);
                String image = readMeta(page, "meta[property='og:image']", "content");
                Availability availability = readAvailability(page, flipkart);
                // Amazon often renders several amounts on the same page (for example,
                // crossed-out list prices and other offers). Always prefer its current
                // buy-box price over a user-supplied, potentially broad CSS selector.
                BigDecimal amount = amazon ? readAmazonBuyBoxPrice(page) : null;
                if (amount == null && flipkart) amount = readFlipkartPrice(page);
                if (amount == null && !amazon && customSelector != null) {
                    amount = parseNumber(readSelector(page, customSelector));
                }
                if (amount == null && !amazon) amount = readStructuredPrice(page);
                if (amount == null && !amazon) {
                    for (String selector : META_PRICE_SELECTORS) {
                        amount = parseNumber(readMeta(page, selector, "content"));
                        if (amount != null) break;
                        amount = parseNumber(readSelector(page, selector));
                        if (amount != null) break;
                    }
                }
                if (amount == null && availability != Availability.UNKNOWN) {
                    return new PriceReading(null, readCurrency(page), image, availability);
                }
                if (amount == null && amazon) {
                    throw new IllegalStateException("Amazon did not expose a current buy-box price for this product. No fallback price was used to avoid recording a list price or another page value.");
                }
                if (amount == null && !amazon && !flipkart) amount = readVisiblePrice(page);
                if (amount == null || amount.signum() <= 0) {
                    if (flipkart) {
                        throw new IllegalStateException("Flipkart did not expose a current product price. It may be showing a bot check or location prompt; no unrelated page number was recorded.");
                    }
                    throw new IllegalStateException("No price was found. Try adding a CSS price selector for this page.");
                }
                String currency = readCurrency(page);
                return new PriceReading(amount, currency, image, availability);
            } finally {
                browser.close();
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read this product page: " + exception.getMessage(), exception);
        }
    }

    private void guardRequest(Route route) {
        try {
            URI uri = URI.create(route.request().url());
            if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null || isPrivate(uri.getHost())) {
                route.abort();
            } else {
                route.resume();
            }
        } catch (Exception exception) {
            route.abort();
        }
    }

    private boolean isPrivate(String host) throws Exception {
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        }
        return false;
    }

    private BigDecimal readStructuredPrice(Page page) {
        for (String json : page.locator("script[type='application/ld+json']").allTextContents()) {
            try {
                BigDecimal value = findOfferPrice(objectMapper.readTree(json));
                if (value != null) return value;
            } catch (Exception ignored) {
                // Ignore malformed third-party structured data and try the next signal.
            }
        }
        return null;
    }

    private BigDecimal findOfferPrice(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            for (JsonNode child : node) {
                BigDecimal found = findOfferPrice(child);
                if (found != null) return found;
            }
            return null;
        }
        if (node.isObject()) {
            JsonNode offers = node.get("offers");
            if (offers != null) {
                BigDecimal found = findPriceField(offers);
                if (found != null) return found;
            }
            Iterator<java.util.Map.Entry<String, JsonNode>> properties = node.properties().iterator();
            while (properties.hasNext()) {
                BigDecimal found = findOfferPrice(properties.next().getValue());
                if (found != null) return found;
            }
        }
        return null;
    }

    private BigDecimal findPriceField(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                BigDecimal value = findPriceField(child);
                if (value != null) return value;
            }
        } else if (node.isObject()) {
            for (String key : List.of("price", "lowPrice")) {
                JsonNode price = node.get(key);
                if (price != null) {
                    BigDecimal value = parseNumber(price.asText());
                    if (value != null) return value;
                }
            }
        }
        return null;
    }

    private String readMeta(Page page, String selector, String attribute) {
        try { return page.locator(selector).first().getAttribute(attribute); }
        catch (RuntimeException ignored) { return null; }
    }

    private String readSelector(Page page, String selector) {
        try {
            var locator = page.locator(selector).first();
            // Playwright's getAttribute/textContent waits up to the default timeout when
            // a selector has no match. Amazon's layout varies per product, so probe
            // existence first instead of spending a timeout on every absent selector.
            if (locator.count() == 0) return null;
            String content = locator.getAttribute("content");
            return content == null ? locator.textContent() : content;
        } catch (RuntimeException ignored) { return null; }
    }

    private BigDecimal readVisiblePrice(Page page) {
        String body = page.locator("body").innerText();
        Matcher matcher = NUMBER.matcher(body);
        int count = 0;
        while (matcher.find() && count++ < 40) {
            BigDecimal value = parseNumber(matcher.group());
            if (value != null && value.signum() > 0) return value;
        }
        return null;
    }

    private BigDecimal readFirstPrice(Page page, List<String> selectors) {
        for (String selector : selectors) {
            BigDecimal amount = parseNumber(readSelector(page, selector));
            if (amount != null && amount.signum() > 0) return amount;
        }
        return null;
    }

    private BigDecimal readAmazonBuyBoxPrice(Page page) {
        // Amazon hydrates the buy box after DOMContentLoaded on some product pages.
        // Poll briefly so a manual check reads the refreshed price rather than the
        // initial HTML before the client-side offer data has appeared.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        do {
            BigDecimal amount = readFirstPrice(page, AMAZON_PRICE_SELECTORS);
            if (amount != null && amount.signum() > 0) return amount;
            page.waitForTimeout(400);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private BigDecimal readFlipkartPrice(Page page) {
        // Flipkart fills the product detail after the initial document response.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        do {
            BigDecimal amount = readFirstPrice(page, FLIPKART_PRICE_SELECTORS);
            if (amount != null && amount.signum() > 0) return amount;
            page.waitForTimeout(400);
        } while (System.nanoTime() < deadline);
        return null;
    }

    private Availability readAvailability(Page page, boolean flipkart) {
        if (flipkart) {
            Availability structured = readStructuredAvailability(page);
            if (structured != Availability.UNKNOWN) return structured;

            // Flipkart can render "Not available at this Pincode" for exchange
            // or delivery extras even while the product itself is in stock. Look
            // for explicit product stock text or a purchase action in the main
            // product section instead of classifying every "not available" phrase.
            try {
                String body = page.locator("body").innerText().toLowerCase(Locale.ROOT);
                String productSection = body.substring(0, Math.min(body.length(), 5000));
                if (productSection.contains("currently out of stock") || productSection.contains("out of stock")
                        || productSection.contains("sold out") || productSection.contains("currently unavailable")) {
                    return Availability.OUT_OF_STOCK;
                }
                if (productSection.contains("buy now") || productSection.contains("add to cart")) {
                    return Availability.IN_STOCK;
                }
            } catch (RuntimeException ignored) {
                return Availability.UNKNOWN;
            }
            return Availability.UNKNOWN;
        }
        for (String selector : AVAILABILITY_SELECTORS) {
            Availability found = classifyAvailability(readSelector(page, selector));
            if (found != Availability.UNKNOWN) return found;
        }
        try {
            String body = page.locator("body").innerText();
            return classifyAvailability(body.substring(0, Math.min(body.length(), 6000)));
        } catch (RuntimeException ignored) {
            return Availability.UNKNOWN;
        }
    }

    private Availability readStructuredAvailability(Page page) {
        for (String json : page.locator("script[type='application/ld+json']").allTextContents()) {
            try {
                Availability found = findOfferAvailability(objectMapper.readTree(json));
                if (found != Availability.UNKNOWN) return found;
            } catch (Exception ignored) {
                // Ignore malformed structured data and inspect the next block.
            }
        }
        return Availability.UNKNOWN;
    }

    private Availability readOfferAvailability(JsonNode offers) {
        if (offers == null) return Availability.UNKNOWN;
        if (offers.isArray()) {
            for (JsonNode offer : offers) {
                Availability found = readOfferAvailability(offer);
                if (found != Availability.UNKNOWN) return found;
            }
            return Availability.UNKNOWN;
        }
        if (!offers.isObject()) return Availability.UNKNOWN;
        JsonNode availability = offers.get("availability");
        if (availability == null) return Availability.UNKNOWN;
        String value = availability.asText().toLowerCase(Locale.ROOT);
        if (value.endsWith("/instock") || value.endsWith("/limitedavailability")) return Availability.IN_STOCK;
        if (value.endsWith("/outofstock") || value.endsWith("/soldout") || value.endsWith("/discontinued")) {
            return Availability.OUT_OF_STOCK;
        }
        return Availability.UNKNOWN;
    }

    private Availability findOfferAvailability(JsonNode node) {
        if (node == null) return Availability.UNKNOWN;
        if (node.isArray()) {
            for (JsonNode child : node) {
                Availability found = findOfferAvailability(child);
                if (found != Availability.UNKNOWN) return found;
            }
            return Availability.UNKNOWN;
        }
        if (node.isObject()) {
            JsonNode offers = node.get("offers");
            if (offers != null) {
                Availability found = readOfferAvailability(offers);
                if (found != Availability.UNKNOWN) return found;
            }
            Iterator<java.util.Map.Entry<String, JsonNode>> properties = node.properties().iterator();
            while (properties.hasNext()) {
                Availability found = findOfferAvailability(properties.next().getValue());
                if (found != Availability.UNKNOWN) return found;
            }
        }
        return Availability.UNKNOWN;
    }

    private Availability classifyAvailability(String text) {
        if (text == null || text.isBlank()) return Availability.UNKNOWN;
        String normalized = text.toLowerCase(Locale.ROOT);
        if (normalized.contains("currently unavailable") || normalized.contains("out of stock")
                || normalized.contains("sold out") || normalized.contains("currently not available")
                || normalized.contains("temporarily unavailable")) return Availability.OUT_OF_STOCK;
        if (normalized.contains("in stock") || normalized.contains("available for delivery")
                || normalized.contains("available now") || normalized.contains("add to basket")
                || normalized.contains("add to cart")) return Availability.IN_STOCK;
        return Availability.UNKNOWN;
    }

    private boolean isAmazonHost(String productUrl) {
        try {
            String host = URI.create(productUrl).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("amazon.") || host.contains(".amazon.");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isFlipkartHost(String productUrl) {
        try {
            String host = URI.create(productUrl).getHost();
            return host != null && (host.equalsIgnoreCase("flipkart.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".flipkart.com"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isAmazonProductPage(String productUrl) {
        try {
            String path = URI.create(productUrl).getPath();
            return path != null && path.matches("(?i).*/(?:dp|gp/product)/[A-Z0-9]{10}(?:/.*)?$");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String readCurrency(Page page) {
        String code = readMeta(page, "meta[itemprop='priceCurrency']", "content");
        if (code == null) code = readMeta(page, "meta[property='product:price:currency']", "content");
        return code == null || code.length() != 3 ? null : code.toUpperCase(Locale.ROOT);
    }

    private BigDecimal parseNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher matcher = NUMBER.matcher(raw.replace('\u00a0', ' '));
        if (!matcher.find()) return null;
        String value = matcher.group().replace(" ", "");
        int comma = value.lastIndexOf(',');
        int dot = value.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            value = comma > dot ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
        } else if (comma >= 0) {
            value = value.substring(comma + 1).length() <= 2 ? value.replace(',', '.') : value.replace(",", "");
        } else if (dot >= 0 && value.substring(dot + 1).length() == 3) {
            value = value.replace(".", "");
        }
        try { return new BigDecimal(value); }
        catch (NumberFormatException ignored) { return null; }
    }
}
