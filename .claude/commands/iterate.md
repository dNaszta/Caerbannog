---
description: Run one task through the Caerbannog team loop (architect, test developer, senior developer, verify, review)
argument-hint: <task id or title, e.g. M2-03 or "stream list">
---

Run the task "$ARGUMENTS" through the team loop. You are the coordinator: delegate to the agents, do not do their work. Each agent starts without your context, so give it the task id, the brief path, the branch and the previous report in its prompt.

## 0. Find the task

- Look in `docs/tasks/` for a brief matching "$ARGUMENTS". If there is none, start at step 1 so the architect writes it. If "$ARGUMENTS" is empty, ask the architect to propose the next task from `docs/spec.md` and the existing briefs, then continue with it.
- Branch name: `<task-id>-<slug>`.

## 1. Brief (solution-architect)

Ask the architect to write or confirm the brief using its template: goal, scope, acceptance (BDD scenarios), design notes, open points. If the brief has an open point that needs a product decision, stop and ask me with options and a recommendation.

## 2. Tests first (test-developer)

Ask the test developer to make sure every acceptance point has a scenario, write the step definitions and fakes, and run them to confirm they fail for the right reason. If it finds the brief unclear or contradicting the spec, send that to the architect, then continue.

## 3. Build (senior-developer)

Ask the senior developer to implement on the branch, red to green to refactor, and to return its handover note. If it reports "blocked", route the question to the right owner (design and spec to the architect, scenarios to the test developer) and resume it with the answer.

## 4. Verify (test-developer)

Ask the test developer to verify the branch and return its test report (scenarios, `@smoke`, `@fake-broker`, `@broker` or why not run, exploratory findings).

## 5. Review (solution-architect)

Ask the architect to review the diff against the brief, spec, scenarios and test report, and return one verdict: Accepted, Accepted with follow-ups, Changes required, or Rejected, with a findings table.

## 6. Decide the next step

- **Accepted**: update the task status to done, list any follow-ups as new tasks (ask the architect to write the briefs), and report to me.
- **Accepted with follow-ups**: same, and list the follow-ups.
- **Changes required**, or a test report with a blocker or major: send the findings to the senior developer (step 3), then verify (step 4) and review (step 5) again. This is one round.
- **Rejected**: stop and ask me, with the architect's reason and proposed direction.
- After **three rounds** without Accepted: stop and ask me, with what keeps failing.

## Status and reporting

After every step update the `## Status` block at the end of `docs/tasks/<id>.md` (step, round, verdict, open findings, open questions, branch) and the line in `docs/tasks/README.md`. Create them if they are missing.

Stop and ask me only for product decisions, a wrong spec, three failed rounds, a new dependency or build-tool choice, or milestone acceptance. Never push, merge or delete without my word.

Finish with: where the task stands, a table of the steps and their results, anything I need to decide, and the files that changed. Say plainly what was not run.
