# Menix AI Job Assistant

This project is the existing Electron + Spring Boot job assistant, with its dashboard styled to follow the supplied Menix references: charcoal/navy surfaces, electric-blue primary actions, green match indicators, a collapsible icon sidebar, six recommendations, a status donut, and source health. The Electron app menu and visible scrollbars are hidden; scroll behavior remains available.

## Run on Windows

1. Extract this ZIP.
2. Start the backend in PowerShell:
   ```powershell
   cd backend
   mvn spring-boot:run
   ```
   The backend uses port 8080 by default. If it is already occupied, stop the old Menix backend or configure a different port and update `API_BASE` in `src/features/job-assistant/job-assistant.js`.
3. In a second PowerShell terminal, return to the project root and run:
   ```powershell
   npm install
   npm start
   ```

## Dashboard behavior

- On desktop, the sidebar stays collapsed to icons until hovered or keyboard-focused, then expands to show navigation labels. Choosing a Workspace item keeps it expanded until you click elsewhere; the account row sits directly above backend status and the Pro upsell remains removed. Narrow phones keep an icon rail.
- Use the account avatar button to choose a PNG, JPG, or WebP profile photo. The image is center-cropped, saved locally on this device, and shown in both the sidebar and dashboard navbar.
- The Electron window uses an integrated dark title bar with working Apple-style close, minimize, and maximize controls.
- Dashboard recommendations are limited to the six highest-scoring real jobs discovered in the last seven days. At wide desktop widths they appear in a 3-column × 2-row grid; smaller viewports reflow to two or one columns.
- The dashboard requests only the top 50 matching listings, skips an unused expensive match-count statistic, and caches successful GET responses for one minute to keep section navigation responsive.
- Application progress and source health stay in full-width stacked cards when the dashboard narrows, so their charts and connection labels remain readable.
- Job results, source health, and application status are loaded from the local Spring Boot API; the dashboard does not fabricate connection states.
- After replacing/updating project files, restart the Spring Boot process from `backend` so new API routes (including Messages & Outreach) are loaded. If an old backend is still running, the Messages page reports that it needs updating instead of showing an unexplained 404.
- Dashboard search sends the entered title and location to Menix's synced job listings and opens a matching Google Jobs search in the external browser. Google results remain external and are not imported into Menix; save a specific listing with **Add a job manually** if you want to track it.
- The New Jobs page defaults to recent listings without hiding lower-scoring results; its minimum-match control is independent of the Strong Matches dashboard threshold.
- Configured job sources sync automatically once per day when the app opens; the backend also retains its scheduled sync. Provider rate limits and reported source failures remain visible.
- The dashboard KPI cards span the content width and use the supplied blue, green, purple, and orange reference style. **Refresh jobs** checks all built-in feeds and configured employer boards and reports provider errors.
- The Jobs page and Application tracker use compact opportunity/status cards, and the top-right dashboard controls include notifications, theme, and profile actions.
- Select **Details** (or the three-dot job-card control) to open the full job description and match details. Use **Back**, the app's browser-history back action, or a two-finger left-to-right touchpad swipe to return to the previous section.
- Saved-job bookmarks use the existing application record with `SHORTLISTED` status; rejected jobs remain available for restoration in the application tracker.
- KPI values and donut segments are derived from API job/application/source records. Match scores come from the backend matching service.
- Provider support and submission permissions remain source-dependent; the dashboard visual refresh does not create new portal permissions or guarantee universal auto-submit.

## Multi-portal job discovery and outreach

- Built-in public job feeds include Remotive, Arbeitnow, and Jobicy. Greenhouse and Lever job-board APIs require a configured employer board identifier. Remote OK is not synced or included in job discovery; old Remote OK listing records are retained only for any existing application history.
- LinkedIn, Naukri, Indeed, Google Jobs, and company-careers search are external browser shortcuts. Company-careers search groups related skills, entry-level wording, and remote locations as alternatives instead of restricting results to specific careers/jobs subdomains. Search terms and location are encoded into the links. Open the original listing and complete employer applications on the source site.
- **Browser Assistant** embeds an isolated portal browser beside the selected-job panel. Before first use, Menix asks permission to use its saved browser profile; sign-in cookies may remain between app launches. Portal search and job selection remain user-driven. Menix does not scrape results, bypass CAPTCHA/OTP, or submit applications. Enter details you verified on the listing to save it and view its profile match. Closing the embedded tab does not sign you out; sign out on the portal to end a saved login.
- Use **Add a job manually** to save a public HTTPS listing or careers URL. Menix canonicalizes it, prevents duplicate listing URLs, preserves source attribution, and records discovery time separately from any posting date supplied by the provider. A manually saved URL is a bookmark, not a connected or automatically synced feed.
- **Portal capabilities** in Sources reports job-search/detail capability separately from account, inbox, messaging, and submission access. This build does not connect LinkedIn, Naukri, Indeed, or employer inboxes, and does not claim any portal inbox is connected.
- **Messages & Outreach** stores recruiter drafts, contacts, related job URLs, notes, and optional follow-up dates locally in the backend database. Copy a draft and open the portal to send it yourself. Menix records it as sent only after you explicitly confirm the manual action and add evidence; it never sends recruiter messages.
- Application drafts and profile-based cover-letter text are editable. Starting an application opens the employer form for the user to review and submit. Profile autofill is opt-in, only fills recognized blank fields after confirmation, and is disabled on LinkedIn. Saved resumes can be selected and downloaded from the application workspace for manual attachment; Menix never sets file inputs or submits forms. The tracker requires confirmation evidence before changing an application to `SUBMITTED`.
- Public URLs must use HTTPS. Manual source testing disables redirects and blocks local/private-network hostnames and addresses. Review each provider's terms and rate limits before use; the source test is only a reachability and format hint, not permission to collect listings.

### Connecting job sources and refreshing listings

1. Open **Sources** to see the actual source status. Remotive, Arbeitnow, and Jobicy are public feeds; Menix checks them automatically on its daily schedule. No job-board password or portal account is needed.
2. For employer boards hosted by Greenhouse or Lever, find the company board identifier in its public careers URL. For example, `boards.greenhouse.io/acme` uses `acme`; `jobs.lever.co/acme` uses `acme`.
3. In the PowerShell window used to start the backend, set the identifiers and restart Spring Boot:
   ```powershell
   $env:GREENHOUSE_BOARD_TOKENS="acme,contoso"
   $env:LEVER_SITE_NAMES="acme,contoso"
   mvn spring-boot:run
   ```
   Set only the variable(s) for boards you want. These are public posting identifiers, not account credentials. After restart, the boards appear in **Sources** and are checked by **Refresh jobs**.
4. On the Dashboard, click **Refresh jobs** (or use **Sync sources** in New Jobs) to check all built-in feeds and configured employer boards now. Dashboard recommendations show the six strongest matching recent listings. The Jobs page shows the wider listing set.
5. LinkedIn, Naukri, Indeed, Google Jobs, and arbitrary company careers pages are external/manual in this build. Use **Browser Assistant** to open a portal, search there yourself, and save a selected listing from details you reviewed. A dashboard search opens Google Jobs with the entered title and location while Menix filters its own synced listings. Saving or testing a URL does not connect the account or import listings.

## Security and verification notes

- The Content Security Policy remains enabled; the chart uses SVG and does not require a third-party chart library or inline styles.
- Run `node --check src/features/job-assistant/job-assistant.js` to verify frontend syntax.
- Run backend tests from the project root with `mvn -f backend/pom.xml test`.
- The multi-portal workflow tests cover external capability claims, manual URL canonicalization/deduplication, outreach draft persistence and send confirmation, URL validation, and the submitted-application evidence gate.
- Inspect the desktop layout in Electron at the supported window sizes before production use. Provider terms and API access remain source-dependent.
