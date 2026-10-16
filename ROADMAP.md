# Price Monitor Project Plan

This roadmap keeps work small and focused. Complete and verify one feature before moving to the next.

## Milestone 1 — Basic price tracking

**Status: Complete**

Let a user track a product from a public product page and see how its price changes over time.

### Completion checklist

- [x] Add a product name, product page URL, currency, and optional target price.
- [x] Save tracked products and price history in PostgreSQL.
- [x] Read the product price and show the latest price in the watchlist.
- [x] Let the user request a price check and run checks on a schedule.
- [x] Keep product-specific price history for comparison between checks.
- [x] Avoid replacing the last successful price when a check fails.

## Upcoming work

Choose one item at a time and add its acceptance criteria here before implementation.

- [ ] Improve retailer-specific price and availability detection, starting with the stores the user tracks most.
- [ ] Add clearer check diagnostics so a blocked page, missing price, and unavailable product are easy to distinguish.
- [ ] Review alert behavior and let users choose which price or availability changes should trigger a notification.

## Working agreement

For each feature:

1. Define the user-visible behavior and completion criteria.
2. Implement the smallest complete change.
3. Build and manually verify it with a representative product page.
4. Update this roadmap with the result and select the next item.
5. Commit the finished change with a concise message.
