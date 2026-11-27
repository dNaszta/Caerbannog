# Ship THIRD_PARTY_NOTICES.md and the licence texts in the installers

Milestone: M10    Depends on: M10-04 step 3 (RE2/J on the runtime classpath)    Size: S

## Goal
Every installer (`.dmg`, `.msi`, `.rpm`, `.deb`) contains `THIRD_PARTY_NOTICES.md` and the `licenses/` folder (at least `RE2J-LICENSE.txt`), so the BSD-3-Clause notice requirement is met for binary redistribution.

## Why
Today `desktopApp/build.gradle.kts` passes only `licenseFile = LICENSE`; the notices live in the repo only. RE2/J (BSD-3-Clause) and the other notice-bearing dependencies need the notice in binaries. Found in M10-04 step 2 (architect ruling 4).

## Scope
In: copy the two items into the app resources (`compose.desktop` `appResourcesRootDir` or a resources directory read at run time) and make them reachable, for example from the About or licence text if one exists (do not add a new screen); a build check that the packaged app contains them (a Gradle task test or a packaging CI step on one OS).
Out: changing which dependencies are used; a new About screen (needs the owner).

## Acceptance
- A test (Gradle verification or unit test over the resource list) fails when `THIRD_PARTY_NOTICES.md` or `licenses/RE2J-LICENSE.txt` is missing from the app resources.
- Packaged on the current OS: the files are present in the installed app directory (record the path in the commit message; other OS by `package.yml`).

## Design notes
`desktopApp` build only; no production code in `core`. jpackage cannot cross-build, so only the current OS is checked by hand.

## UX
None.

## MCP
None (internal, packaging).

## Open points
Whether `mcp-server` has a distributed artefact that needs the same files: not checked.

## Definition of done
Test first; no unrelated change; `THIRD_PARTY_NOTICES.md` mentions where the notices are shipped.
