package com.pricemonitor.scrape;

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
            "#corePrice_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#corePriceDisplay_desktop_feature_div .a-price:not(.a-text-price) .a-offscreen",
            "#apex_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#apex_offerDisplay_desktop .a-price:not(.a-text-price) .a-offscreen",
            "#priceblock_dealprice",
            "#priceblock_ourprice",
            "#priceblock_saleprice",
            "#price_inside_buybox",
            "#newBuyBoxPrice"
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

                String image = readMeta(page, "meta[property='og:image']", "content");
                BigDecimal amount = customSelector == null ? null : parseNumber(readSelector(page, customSelector));
                if (amount == null && isAmazonHost(productUrl)) amount = readFirstPrice(page, AMAZON_PRICE_SELECTORS);
                if (amount == null) amount = readStructuredPrice(page);
                if (amount == null) {
                    for (String selector : META_PRICE_SELECTORS) {
                        amount = parseNumber(readMeta(page, selector, "content"));
                        if (amount != null) break;
                        amount = parseNumber(readSelector(page, selector));
                        if (amount != null) break;
                    }
                }
                if (amount == null) amount = readVisiblePrice(page);
                if (amount == null || amount.signum() <= 0) {
                    throw new IllegalStateException("No price was found. Try adding a CSS price selector for this page.");
                }
                String currency = readCurrency(page);
                return new PriceReading(amount, currency, image);
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
