# Price Monitor Playwright Functional Test Cases

Run against the local app at `http://localhost:5173` with PostgreSQL running and the Java 25 API available on port 8080. These cases use temporary data only; remove any created test product after the run.

| ID | Scenario | Steps | Expected result |
| --- | --- | --- | --- |
| PM-FN-001 | Dashboard loads with an empty watchlist | Open the app with an empty database. | Dashboard renders, shows the empty-state prompt and zero counts, with no request error. |
| PM-FN-002 | Product form enforces required fields | Open **Track a product** and submit without filling fields. | Form stays open, browser required-field validation blocks submission, and no product is created. |
| PM-FN-003 | Private product URLs are rejected | Fill the product name and set the URL to `http://127.0.0.1`, then submit. | API rejects the request with HTTP 400, the UI shows an error, and the watchlist remains unchanged. |
| PM-FN-004 | Push alerts show an unconfigured state safely | Start with blank VAPID values and load the dashboard. | Push configuration request succeeds; UI explains that VAPID keys are needed; dashboard remains usable. |
| PM-FN-005 | Public product can be added and displayed | Submit a reachable public product URL with a name and target price. | The request completes, the product appears in the watchlist, and the tracked count increments even if the retailer has no readable price. |
| PM-FN-006 | Product can be removed | Remove the temporary product from PM-FN-005 and accept the confirmation. | Product disappears, count returns to its prior value, and it stays absent after reload. |
| PM-FN-007 | Watchlist survives a page reload | Reload after a successful product creation. | The same product and its saved details are rendered from the API. |
