# Price Monitor

A local price-tracking app built with Java 25, Spring Boot, React, TypeScript, PostgreSQL, and Playwright. Add a public product page, save its current price, and keep a price history as the app checks it on a schedule.

The first version reads common product metadata, JSON-LD offers, and visible page text. Sites with unusual markup can use a CSS price selector. Retailers can change their pages at any time, so a reading may need a selector adjustment.

## Prerequisites

- Eclipse Temurin JDK 25 (the project compiles with Java 25).
- Maven 3.6.3 or newer.
- Node.js 20.19+ (Node 24 LTS recommended) and npm.
- Docker Desktop with Docker Compose, for PostgreSQL.
- IntelliJ IDEA Community Edition or another Java/TypeScript editor.

Spring Boot 4.1.1 supports Java 25. The Maven version bundled with this PC (3.6.3) meets the Spring Boot build requirement and has been confirmed to start on Temurin 25.

## First-time setup on Windows

From the repository root in PowerShell:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set `DB_USERNAME` and `DB_PASSWORD` in `.env`. Use a strong local password. `.env` is ignored by Git; do not commit it.

Install the project’s Chromium browser with the Playwright version declared in the Maven build:

```powershell
cd backend
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
cd ..
```

On Windows, Playwright installs the browser runtime into the current user’s local Playwright cache. Its Linux `install-deps` command is not needed for a native Windows setup.

## Run locally

Open three terminals from the repository root.

Terminal 1, start PostgreSQL:

```powershell
docker compose up -d postgres
```

Terminal 2, start the API:

```powershell
cd backend
mvn spring-boot:run
```

Terminal 3, start the web app:

```powershell
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. The API listens on <http://localhost:8080>.

Stop the web app and API with `Ctrl+C`. Stop PostgreSQL with `docker compose down`; its data stays in the `postgres-data` volume. To remove that database data too, use `docker compose down -v`.

## IntelliJ IDEA Community Edition

1. Open the repository root in IntelliJ IDEA Community Edition.
2. Set the Project SDK to the installed Temurin JDK 25. IntelliJ’s Maven importer should use that project SDK; verify it under **Settings → Build, Execution, Deployment → Build Tools → Maven → Runner**.
3. Open `backend/pom.xml` and load it as a Maven project.
4. Create the root `.env` as described above, then run PostgreSQL with `docker compose up -d postgres` from the terminal.
5. Run `com.pricemonitor.PriceMonitorApplication` with the working directory set to `backend`, or use the Maven `spring-boot:run` goal.
6. Run the frontend separately from `frontend` with `npm install` and `npm run dev`.

## API overview

- `GET /api/products` — list tracked products and recent price history.
- `POST /api/products` — add a product and attempt an immediate reading.
- `GET /api/products/{id}` — get one product.
- `PUT /api/products/{id}` — update its name, URL, target, currency, or CSS selector.
- `POST /api/products/{id}/check` — check its price now.
- `DELETE /api/products/{id}` — remove it and its history.

Automatic checks run every 30 minutes by default. Set `MONITOR_INTERVAL_MS` to change the interval. Target prices are stored and displayed; notification delivery is not included in this first version.

## Credentials and local boundaries

Database credentials come from `.env` or environment variables. No Telegram tokens or email passwords are stored in the repository. The API is intended for local use and has no account authentication. Product URLs must use public HTTP or HTTPS hosts; requests to private and loopback addresses are blocked to prevent the scraper from reaching local services.
