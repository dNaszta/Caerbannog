# M9-03: Planner screen: the step list, the wizard dialog, the entry on the Migrations screen, the limited states

Milestone: M9    Depends on: M9-02    Size: M

PLANNED 2026-11-15. Not started. Third task of the earliest working slice: after it a user can plan a queue, an exchange and a binding, save the plan as a migration and apply it on the Migrations screen.

## Goal
The planner is on screen as drawn in `docs/ux/Planner.dc.html`: opened from the Migrations screen, with the plan header, the step list, the three-page wizard, the JSON view, save, dismiss, and every limited state. The composables are thin: every decision and text comes from `PlannerState`.

## Why
Spec "Topology planner (M9)" (owner decisions O1, O3, O5), Screens table row "Topology planner". The mockup is the design source.

## Scope
In:
- `ui`, package `dev.caerbannog.ui.planner`: `PlannerScreen(state, events, control, controlEvents, onNavigate)`, `PlanHeader` (description, virtual host, naming style menu, draft pill), `PlanStepList` (number, icon, title, detail, row menu Edit, Move up, Move down, Remove; Alt with Up or Down moves the focused step), `PlanWizardDialog` (Kind, Details, Summary; arrow keys between kinds, Enter is Next, Esc cancels), the forms of `M9-01` (`QueueFormView`, `ExchangeFormView`, `BindingFormView` with a suggestion list), `PlanJsonView` (the migration text, read only, Copy through `rememberCopyToClipboard()`), the dismiss and move-aside dialogs, the banners.
- `PlannerEvents` interface in `ui` and `PlannerEventsAdapter` in `desktopApp` (the pattern of `TopologyEventsAdapter`).
- The Migrations screen (`MigrationsScreen.kt`): the first app bar button "New plan", or "Continue plan" when a draft exists, and the draft card above the files (title, description, step count, Continue plan, Dismiss). No other change to that screen in this task.
- `desktopApp`: the planner is a sub-screen of the Migrations destination (the drawer keeps Migrations selected; `AppNavigation.openPlanner()` and `closePlanner()`, in the way `openStream` works for Streams); the composition root builds `PlannerViewModel` from the same inputs as the Migrations view model and the same `MigrationFolderPorts`; after `SavedPlan` it closes the planner and opens the Migrations screen with that version selected and the banner "Plan saved as <version>. Nothing was sent to the broker. Apply it when you are ready."
- Test tags (stable from the start): `planner:title`, `planner:addStepBtn`, `planner:saveBtn`, `planner:dismissBtn`, `planner:checkBtn`, `planner:namingChip`, `planner:namingOption:<style>`, `planner:description`, `planStep:<index>`, `planStepMenu:<index>`, `planStepProblem:<index>`, `planner:banner`, `planner:emptyState`, `planWizard:kind:<kind>`, `planWizard:next`, `planWizard:back`, `planWizard:cancel`, `planWizard:add`, `planWizard:summary`, `planForm:<field>`, `planForm:hint:<field>`, `planForm:error:<field>`, `planner:jsonView`, `migrations:newPlanBtn`, `migrations:draftCard`.

Out: the review graph (`M9-04`; until then the review card shows the JSON view only); the check button's behaviour (`M9-06`; until then the button is not drawn); "Edit steps" (`M9-12`); stream, policy, dead-lettering and MQTT kinds (their cards appear in the wizard with the task that builds each form; a kind that is not built is not drawn).

## Acceptance
- BDD: `09_topology_queues.feature` / "Topology is changed with a plan, not on the Topology screen" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "The planner is opened from the Migrations screen" (@fake-broker)
- BDD: `19_topology_planner.feature` / "After saving, Apply is pressed on the Migrations screen" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A Read-only connection can plan, check and save, and is told that applying is locked" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "Without configure permission the planner says that applying is locked" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A RabbitMQ 3.x connection gets no planner" (@fake-broker @negative @safety)
- BDD: `19_topology_planner.feature` / "Without a connection a plan can be written and saved but not checked" (@fake-broker)
- BDD: `19_topology_planner.feature` / "The plan can be read as the migration text it will be saved as" (@fake-broker)
- Compose UI tests, written first (1280 x 800, the kit of the Migrations tests): `PlannerScreenUiTest` (header, empty state, step list, row menu, keyboard move, JSON view not editable), `PlanWizardUiTest` (three pages, keyboard, disabled Next with the reason under the field, hint shown and Next enabled, summary, cancel adds nothing, focus on the first new step after "Add to plan"), `PlannerStatesUiTest` (one test per banner of mockup section 12, and that the 3.x state draws no wizard and no save), `PlannerDismissUiTest`, `MigrationsNewPlanUiTest` (the button, "Continue plan", the draft card), `PlannerScreenshotTest` (opt-in tag `screenshot`, one file per mockup section built here).
- `desktopApp`: `AppNavigationPlannerTest` (open, close, the drawer item stays Migrations, a 3.x connection cannot open it), `CompositionRootPlannerWiringTest` (same folder store as the Migrations screen; after a save the Migrations screen shows the version selected).
- By eye: the screenshots against `docs/ux/Planner.dc.html`; a narrow window (1024 px) wraps the app bar buttons and keeps the title (the known defect `M8-14c` of the Migrations app bar must not be repeated here).

## Design notes
No decision in a composable: enabled, disabled, texts, order of banners all come from the state. The step list is a `LazyColumn` (a plan can come from a large file in `M9-12`). Scroll containers follow the CLAUDE.md rule (no `weight` or ellipsis inside a scroll; a scrollbar for mouse users). The wizard is a dialog; its content scrolls when the window is short. Reuse the shared components (`SearchField` is not needed; `CenteredMessage`, the banner and pill composables of the Migrations screen). No broker is touched by this task; UI tests run on fakes.

## UX
`docs/ux/Planner.dc.html`: the main block (header, step list, review card with the JSON view), "1. Entry", "2. Empty plan", "3. Wizard, page 1", "4." (Queue, Exchange, Binding), "5. Summary", "6. Naming style", "7. Step list" (problems), "10. Save as migration", "11. Dismiss", "12. Blocked and limited states". States to build: empty, draft with steps, problem in the plan, save disabled with its reason, saved, write failed, dismiss question, 3.x, unreadable draft, Read-only level, no configure permission, not connected, no folder and read-only folder, another virtual host.

## MCP
None (UI of a capability that has no tool by owner decision 2026-11-15).

## Open points
- Q11 (decided 2026-11-15): the entry is "New plan" on the Migrations screen and Apply stays on the Migrations screen after a save. A drawer item or Apply inside the planner would be a new decision.
- Q15 (decided 2026-11-15): the Topology screen stays read-only; the `@m9-03` outline in `09` ("Topology is changed with a plan, not on the Topology screen") is bound by this task's suite, which also selects feature `09` for `@M9` scenarios.
- Q16 ("Edit steps" beside "Edit file", decided) belongs to `M9-12`.
- The owner reviews the mockup before step 2 of this task (the owner also confirms the revised Q3 there).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
