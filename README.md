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
- Provider support and submission permissions remain source-dependent; the dashboard visual refresh does not create new portal permissions or guarantee universal application support.

## Multi-portal job discovery and outreach

- Built-in public job feeds include Remotive, Arbeitnow, and Jobicy. Greenhouse and Lever job-board APIs require a configured employer board identifier. Remote OK is not synced or included in job discovery; old Remote OK listing records are retained only for any existing application history.
- LinkedIn, Naukri, Indeed, Google Jobs, and company-careers search are external browser shortcuts. Company-careers search groups related skills, entry-level wording, and remote locations as alternatives instead of restricting results to specific careers/jobs subdomains. Search terms and location are encoded into the links. Open the original listing and complete employer applications on the source site.
- **AutoApply** combines the isolated portal browser and application controls in one workspace. Before first use, Menix asks permission to use its saved browser profile; sign-in cookies may remain between app launches. Portal search and job selection remain user-driven. Menix does not scrape results or bypass CAPTCHA/OTP. Use **Prepare Application** or Apply on a saved job to open the official application form in the embedded browser; popup links stay in that browser instead of opening another window. Detect fields, autofill, review, upload, and submit controls are available beside the employer form. Submission requires your explicit confirmation and an employer confirmation before the tracker records it. Closing the embedded tab does not sign you out; sign out on the portal to end a saved login.
- Use **Add a job manually** to save a public HTTPS listing or careers URL. Menix canonicalizes it, prevents duplicate listing URLs, preserves source attribution, and records discovery time separately from any posting date supplied by the provider. A manually saved URL is a bookmark, not a connected or automatically synced feed.
- **Portal capabilities** in Sources reports job-search/detail capability separately from account, inbox, messaging, and submission access. This build does not connect LinkedIn, Naukri, Indeed, or employer inboxes, and does not claim any portal inbox is connected.
- **Messages & Outreach** stores recruiter drafts, contacts, related job URLs, notes, and optional follow-up dates locally in the backend database. Copy a draft and open the portal to send it yourself. Menix records it as sent only after you explicitly confirm the manual action and add evidence; it never sends recruiter messages.
- From **Jobs**, select Apply to open the official HTTPS application page inside AutoApply. Confirm that the employer permits assisted interaction before detecting or filling fields. The Prepare Application panel distinguishes answered, autofilled, suggested, blank, invalid, and unsupported controls, counts required blanks and recognizable submit controls, explains unmapped questions, and supports a fresh rescan. Smart Autofill applies only high-confidence non-sensitive profile facts and, when explicitly enabled in Settings, high-confidence approved answers. Suggested answers remain editable and require your approval; legal/eligibility answers, CAPTCHA/security challenges, custom widgets, and unsupported controls remain manual. The final review rescans the live page and submission stays disabled while any required control is blank or invalid.
- **Smart Apply Memory** in Settings stores facts you explicitly save to your candidate profile and an approved answer library. Matching is deterministic and confidence-based; it does not train a model. Automatic reuse and answer learning can be disabled, saved answers can be excluded or corrected, and individual or all answer history can be deleted. Sensitive saved answers are shown only for explicit confirmation and are never automatically filled. Unmatched low-confidence questions stay blank.
- **Optional AI suggestions require consent for each request.** Menix sends selected resume text, relevant saved job details, and eligible non-sensitive unanswered question labels to the configured Gemini provider only after confirmation. Resume text may contain personal information. Returned drafts are suggestions only and are never filled automatically; the user must review and explicitly approve each answer. External AI can be left unconfigured without affecting basic profile and approved-answer matching.
- **Resume uploads use the employer page's native file chooser.** Menix detects visible file controls but does not select a local file. Choose the intended resume yourself; the selected file goes directly through the employer page's normal upload flow. The saved resume can be downloaded in Menix first.
- **Submission is deliberate and verified.** The Submit Application action appears only after a fresh review finds no visible required fields left blank and one identifiable submit control. Menix asks for confirmation before clicking that control. The tracker is marked `SUBMITTED` only when a recognizable employer confirmation appears; otherwise the status remains unconfirmed for manual follow-up. Duplicate application records are prevented by the existing job/application workflow.
- **Platform limits:** LinkedIn automation is disabled. Google Forms, Greenhouse, Lever, Ashby, Workday, and Workable hosts are recognized, but provider-specific adapters are not validated; only generic accessible controls are available and manual completion may be required. Google Forms may use custom/cross-origin controls. Check the employer's current terms before use. Unsupported or prohibited flows must be completed manually.
- Changing an application to `WITHDRAWN` hides it from the active application tracker and returns to **Jobs**. Choosing `NOT_COMPLETED_YET` does the same and makes the job available to start again; both keep the application record. The status editor opens inside the selected application card.
- AutoApply uses `/api/profile`, `/api/resumes`, `/api/resumes/application-draft`, `/api/applications`, and `/api/autofill/*`. Smart Apply Memory adds `GET/POST /api/smart-apply/answers`, `PUT/DELETE /api/smart-apply/answers/{id}`, `POST /api/smart-apply/answers/{id}/used`, collection deletion for answer history, and `GET/PUT /api/smart-apply/preferences`. Persistent records are stored in additive `smart_apply_answer` and `smart_apply_preferences` tables; new user-supplied profile fields are additive columns on `candidate_profile`. Hibernate's existing `ddl-auto=update` creates these additively; no existing application or answer history is deleted.
- The application webview uses context isolation, sandboxing, disabled Node integration, HTTPS navigation restrictions, and no website preload. The preload bridge exposes only the desktop actions needed by the Menix renderer; form interaction remains in the isolated application webview.
- Public URLs must use HTTPS. Manual source testing disables redirects and blocks local/private-network hostnames and addresses. Review each provider's terms and rate limits before use; the source test is only a reachability and format hint, not permission to collect listings.

Run the local AutoApply engine and mock-form/security tests from the project root:

```powershell
npm test
```

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
5. LinkedIn, Naukri, Indeed, Google Jobs, and arbitrary company careers pages are external/manual in this build. Use **AutoApply** to open a portal, search there yourself, and save a selected listing from details you reviewed. A dashboard search opens Google Jobs with the entered title and location while Menix filters its own synced listings. Saving or testing a URL does not connect the account or import listings.

### Job freshness and reconciliation

- Feed observations keep provider posting time separate from first discovery, last observation, and last verification. Jobs are recommended only when a source observed them open within the configured verification window; the default is 48 hours and can be changed with `JOB_VERIFICATION_MAX_AGE_HOURS`.
- The Dashboard date selector filters by posting date (24 hours, 7 days, older, or all dates). Exact provider timestamps are required for the 24-hour window; date-only values can qualify for wider windows, while unknown dates are labeled unverified. A wider date filter does not bypass the verification window.
- Source sync attempts, last successful sync, per-run counts, and the latest error are persisted in the local database. Failed requests do not close jobs or refresh their verification timestamps. Jobs omitted by a feed are retained conservatively.
- Manually saved portal links are bookmarks, not automatically verified openings. Legacy records without verification metadata remain unverified until a configured feed genuinely observes them.
- Use **Sources → Reconcile job data** to mark legacy, stale, and invalid records for review. The operation does not delete jobs or application history; it reports duplicate URL candidates and preserves saved/application records.

## Security and verification notes

- The Content Security Policy remains enabled; the chart uses SVG and does not require a third-party chart library or inline styles.
- Run `node --check src/features/job-assistant/job-assistant.js` to verify frontend syntax.
- Run backend tests from the project root with `mvn -f backend/pom.xml test`.
- The multi-portal workflow tests cover external capability claims, manual URL canonicalization/deduplication, outreach draft persistence and send confirmation, URL validation, and the submitted-application evidence gate.
- Inspect the desktop layout in Electron at the supported window sizes before production use. Provider terms and API access remain source-dependent.
