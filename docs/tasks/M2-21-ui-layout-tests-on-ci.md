# Five Compose layout tests fail on the Linux CI runner

Milestone: M2 (test infrastructure, with a possible M9 product defect)    Depends on: none (main 567e3357)    Size: M

## Goal
`xvfb-run ./gradlew test` is green on the GitHub Actions `ubuntu-latest` runner as well as on the owner's Mac: the five layout tests below pass, either because the product layout no longer overflows with the Linux default font, or because the tests no longer depend on the machine's font.

## Why
Main CI has been red on "Fast tests" since at least 2026-11-18 (runs 44728029485, 44631907581, 45074151122: `:ui:jvmTest`, 1337 tests, 5 failed, 2 skipped). A permanently red main hides new breakage, and the spec rule "a task is not done until its scenarios pass" (and the tiered-testing rule: full suite once per task) is not met on the second platform that ships an installer (.rpm, .deb). Linux is a release target, so a real overflow on Linux fonts is a product defect, not only a test problem.

## Evidence (read from the artifact `test-reports` of run 45074151122, `ui/build/reports/tests/jvmTest`)
| Test | Failure on the runner |
| --- | --- |
| `MigrationsAppBarUiTest` "at 1280 px the title, five buttons and the chip share one line" | `the app bar wrapped for Continue plan: [12.0, 12.0, 12.0, 12.0, 12.0, 64.0]`: the five buttons share a line, the connection chip drops to the next line (top 64). The first entry (`NewPlan`) passed; the longer label `ContinuePlan` does not fit. |
| `MigrationsEditStepsUiTest` "at font scale 1_5 the four buttons of the action row are all on screen" | `migrations:applySingleBtn is not displayed` (the fourth button of the row; the test line reported is 249/251). |
| `PlannerEditFileNarrowExploratoryUiTest` "at 900 wide and font scale 1_5" | `planner:editPill is not displayed`. |
| `PlannerCheckExploratoryUiTest` "at 1280 by 800 a checked small plan ..." | `the review card is cut off by the window: Offset(304.0, 524.0) 952 x 324` (bottom 848 > 800). |
| `PlanPairLayoutExploratoryUiTest` "stacked and side by side ... 1 3 7 12 and 30 steps" | At window 1025 x 800 with 3, 7, 12 and 30 steps (not 1): `stacked card ends at 804.0, below the window` (4 px over the 1 px tolerance). |

Pattern: every failure is a small overshoot (a chip, a button, 4 px, a pill) that appears only when text is wider or taller than on the Mac. Nothing is wrong in structure. The runner's default sans font (no bundled app font was found in `ui/src`; to confirm in step 1) is very likely a different, wider face than the Mac system font, and Compose Desktop measures with whatever Skia finds through fontconfig.

## Scope
In: (1) diagnose each of the five on Linux; (2) decide per test: product fix or test fix (rule below); (3) make CI green; (4) make the result stable, so a font change on the runner image cannot flip it again.
Out: new screens, wording changes, a redesign of the app bar or the plan page. If a fix would change visible wording or drop a control, stop and ask the owner (product decision).

## Decision rule (tests or product)
- **Product defect** when a control the user needs (a button, the connection chip, a pill, the review card) is out of the window or wrapped at a size the spec or the UX file promises (1280 x 800; font scale 1.5; 900 wide). Fix the layout in `ui` (weights, wrapping row, overflow menu, scroll) so it holds with a font at least as wide as DejaVu Sans, not just on the runner. The test stays as it is.
- **Test defect** when the assertion encodes a Mac pixel measurement that the spec does not promise (for example the 8 px line tolerance, the 1 px tolerance on the stacked card, `1280f - card width` as "chrome"). Then loosen it to the promise (for example: all tags `isDisplayed`, card inside the window within a stated font tolerance) and write the promise in the test's comment.
- **Never** skip (`@Ignore`, `Assumptions`, `Disabled on Linux`): that hides the Linux defect.
- Where a test only makes sense with fixed metrics, pin the font in the test rig (below), do not weaken the assertion.

## Acceptance
- `xvfb-run -a ./gradlew :ui:jvmTest` green in a Linux container and in CI; the five tests named above pass without being skipped or deleted (names may only change if the promise they state changes; then say so in the Status).
- `./gradlew :ui:jvmTest` stays green on the Mac.
- Each product fix has a test that is red before the fix on a wide font; each test fix says which promise it now asserts.
- A guard against the next drift: either the UI test rig sets one known font for all UI tests (preferred, see design notes) or one new test asserts the layouts with a deliberately wide font (so the overflow shows on the Mac too). The Status says which.
- BDD: no feature file changes expected (these are Compose UI tests, not Cucumber). If a spec promise about window size or font scale is found to be missing, add it to `docs/spec.md` and name the scenario.
- CI "Fast tests" green on the branch (push only with the owner's word; until then a Linux container run is the evidence).

## Design notes
Diagnosis plan, in order (stop at the first that explains all five):
1. **Reproduce locally on Linux.** Run `:ui:jvmTest` with the five classes in a `ubuntu:24.04` container (Podman or Docker; `docs/DEVELOPING_ON_FEDORA.md` and the installer verification already use such containers) with `xvfb-run -a`, the same as `ci.yml`. If it reproduces, iterate there; if not, the runner differs (fonts installed) and step 2 matters.
2. **Record the facts the tests run on.** In a throwaway test (not committed) print `GraphicsEnvironment` font family names, the Skia default typeface family (`FontFamily.Default` resolved through `fc-match sans`), `Density` and the window size in `runDesktopComposeUiTest`. Compare the Mac and the runner. Expected cause: runner default is DejaVu Sans (wider, taller line height) against the Mac system font. Report the real values in the Status; this is the hypothesis, not a fact yet.
3. **Check the other hypotheses cheaply, one line each:** (a) window size: `runDesktopComposeUiTest(w, h)` sets the scene size in px, independent of xvfb, so unlikely; (b) density: xvfb gives 96 dpi, the tests use `Density(1f, ...)`; check no test relies on the platform density; (c) tolerances: 4 px over in `PlanPairLayoutExploratoryUiTest` and the 8 px line check show how thin the margin is even on the Mac; print the Mac values for the same assertions to see the headroom.
4. **Measure the overshoot per test** with the wide font: how many px or dp each overflows. A few px (card 804 vs 800) points at a missing padding or tolerance; a whole button or chip (app bar, action row at 1.5) points at a layout that cannot wrap or shrink, which is a product defect.
5. **Fix by the decision rule.** Likely product work (to confirm): the `MigrationsScreen` app bar row (title, five buttons, chip) needs a wrapping or overflow strategy that keeps the chip on the line or places it deliberately; the action row at font scale 1.5 and the file-mode pills at 900 wide need `FlowRow` or weights. Likely test work: the 1 px tolerance in `PlanPairLayoutExploratoryUiTest` and the "chrome" arithmetic. Keep UX per `docs/ux/` (Material 3 sources); where the UX file shows one line at 1280, keep one line with a font at least as wide as DejaVu Sans, shrinking padding before dropping controls.
6. **Pin the font for UI tests.** Preferred guard: a test-only rig helper (for example in `UiRig` or a shared `setContent` wrapper in `ui/src/jvmTest`) that provides one bundled, license-compatible font (a small OFL font such as DejaVu Sans or Inter in test resources, with its licence line in `THIRD_PARTY_NOTICES.md` only if it ships; a test-only resource does not ship, state that) through the theme typography, so the Mac, Fedora and CI measure identically. Pin to the widest realistic font, not the narrowest. If the product itself ships no font, the product must still be checked against a wide face (step 5), the pin only makes the tests repeatable. No new dependency is needed.
7. **Do not change `ci.yml` to install fonts** to match the Mac: that would make the runner pass while Linux users (Fedora, Ubuntu) still see the overflow. Installing fonts is only acceptable as an addition if the product bundles or declares them.

Boundaries: product fixes only in `ui` (Compose), pure decisions in `core` per the project convention; test changes only in `ui/src/jvmTest`. Keep existing `testTag`s stable. No broker access in any of this, so no safety-rule impact.

## UX
Screens: Migrations app bar and action row (`docs/ux/` migrations screen), plan page step list and review card, planner file mode bar (M9-08, M9-09, M9-12 files). States to hold: connected app bar with every entry label (`NewPlan`, `ContinuePlan`, `DraftUnreadable`), font scale 1.5 at 1280 and at 900 wide, stacked and side-by-side plan layout at 1025 to 1400 px wide, 800 px high. No wording change is intended. Ask the owner only if a control has to be moved into a menu or its label shortened.

## MCP
None (UI-only layout and test infrastructure). No new capability, nothing for `:mcp-server`.

## Open points
- Cause on the runner is a hypothesis (wider default font) until step 2 prints the real family. Status must record it.
- Whether the plan-page card overshoot (4 px) is a real missing margin or only a tight tolerance is unknown until the wide-font measurement.
- Two tests were skipped in the same run (`MigrationsEditStepsNarrowExploratoryUiTest`, 2 tests); check they are skipped on purpose (a `@Ignore` with a reason) and not hiding more Linux failures. Report, do not fix here unless trivial.
- Whether the owner wants a bundled app font for the product (consistent look on all three OS) is a product decision, out of scope here; mention in the Status if the diagnosis makes it attractive.
- Older failures: run 44631907581 and 44728029485 failed on the same five (per the owner's note); I did not diff their reports. Check the five set is identical in step 1.

## Definition of done
Tests first (the wide-font guard red before the product fixes), all five green on Linux and on the Mac, no skips, no unrelated changes, CI "Fast tests" green after the owner pushes, spec and UX notes updated if a promise changed, README line kept in sync, Status filled.

## Status
- Briefed (architect, 2026-11-22), branch `M2-21-ui-layout-tests-on-ci` from main 567e3357. Failure messages above read from the CI artifact `test-reports` of run 45074151122 (job 137367513290). Not run locally; no code touched. Next: step 2 (test developer): Linux container reproduction, font facts, wide-font guard test; then senior developer for product fixes where the rule says product.
