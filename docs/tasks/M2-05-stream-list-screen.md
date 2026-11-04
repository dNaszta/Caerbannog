# Stream list screen (input note)

Milestone: M2    Depends on: M2-04    Size: M

Status: input note; the full brief is written when M2-04 is accepted.

## Inputs from the decisions of 2026-11-04
- `docs/ux/Streams.dc.html` was updated: the Size column is kept but every cell shows a dash and the header has a tooltip ("not available from the broker, left blank until M3").
- Build the states: loading, rows, first offset loading and "unavailable" (cells read "unavailable", counts still shown), empty broker, and the denied state for a user without the management tag (message "This user cannot read stream statistics. Ask for the monitoring tag.", no table). Add the denied and unavailable states to the UX file if missing before building them (ask the architect).
