# AH Flipper 0.2.0 — BIN-only buyer

Fabric client mod for Minecraft 26.2. This version is intentionally **buy-only**:

- Scans Hypixel active auctions and evaluates BIN listings against its in-memory ended-BIN sales history.
- Automatically buys eligible BIN auctions only when Auto-buy is enabled.
- Never bids, claims items, opens the listing flow, or creates auctions.
- After a confirmed purchase, prints the suggested BIN price and estimated net profit in chat.
- Tracks purchased items against your budget and saves them across restarts until you mark them sold with `/flip sold <part of item name>`.

## Commands

- `/flip on` — start scanning.
- `/flip off` — stop scanning and cancel the current GUI task.
- `/flip auto on` — enable automatic BIN purchases.
- `/flip auto off` — disable automatic purchases; scanning/alerts can continue.
- `/flip status` — show scan age, purse, tracked positions, candidate count, and the last API error.
- `/flip positions` — show purchases still counted against your budget.
- `/flip sold <item name>` — remove a tracked item after you sell it manually.
- `/flip purse <coins>` — set a purse override if scoreboard parsing does not work; use `0` to clear it.
- `/flip stop` — cancel the current GUI task. If a purchase was mid-flight, check inventory and purse before restarting.

## Install

Put the built `ahflipper-0.2.0.jar` in your Fabric `mods` folder with Fabric API installed. Start Minecraft, join SkyBlock, and run `/flip on`. Then explicitly enable buying with `/flip auto on`.

## Configuration

`config/ahflipper.json` is generated on first launch. Tracked purchases are saved separately in `config/ahflipper-positions.json`. Important settings:

- `maxFractionPerItem`, `maxCommittedFraction`, `reserveFraction`, `maxOpenPositions`: spending limits.
- `minProfit`, `minMarginPct`, `maxMarginPct`: flip quality thresholds.
- `minSalesPerDay`, `maxHoursToClear`: liquidity filters.
- `undercutPct`: percentage below the estimated fair value used for the suggested listing price.
- `extraFeeCoins`: additional fee allowance in the estimated net-profit calculation.
- `maxPriceIncreasePct`: maximum allowed increase between API price and the live GUI price; defaults to `0` (no overpay).
- `pollSeconds`: API polling interval; defaults to 60 seconds because the public Hypixel endpoints are cached. Lower polling does not bypass server-side caching.

Older config files are merged with current defaults, so new options should not silently become zero or null.

## Price estimates and limitations

The suggested BIN price is the engine's conservative fair value minus `undercutPct`. Estimated net profit subtracts the configured extra fee, the code's assumed 1% tax, and its listing-fee tier estimate. Verify current Hypixel fees and actual market conditions before listing. The recommendation is not a guarantee of sale or profit.

The price key groups by visible item name and rarity; it does not fully represent stars, enchants, reforges, pet level, or all NBT metadata. The existing ignore list therefore excludes some broad/metadata-sensitive categories, but it cannot make every item valuation safe. Treat unusual or high-value items with extra caution.

Public auction API data is cached server-side. This mod cannot see a new auction before the API exposes it, and this code does not bypass Hypixel rate limits. GUI automation and third-party modifications may violate server rules or risk account penalties; use at your own risk and verify the current Hypixel rules.

## Build

The GitHub Actions workflow downloads the Fabric example-mod template, checks that it targets Minecraft 26.2, overlays the Java sources, and runs `./gradlew clean build`. Push to `main` or run the workflow manually. The resulting JAR is uploaded as the `ahflipper-jar` artifact. A `v*` tag also attaches the JAR to a GitHub Release.
