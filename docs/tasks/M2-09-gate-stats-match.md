# Gate: statistics agree with the Management UI (input note)

Milestone: M2    Depends on: M2-03 to M2-06    Size: S    Needs Docker

Status: input note.

## Inputs
- Scenario `03` "Statistics agree with the Management UI" now asserts publisher and consumer counts only (no size). Add the first offset check against `queryStreamStats` of the stream protocol and the message count against management `messages`, with the documented tolerance: management `messages` includes tracking entries of named consumers (spec "Caveats", M2-02c), so the app and the Management UI show the same number; a difference to the number of published messages is allowed up to the tracking entries.
- Poll on values with the spike's bounded rule; statistics lag up to 5 s.
