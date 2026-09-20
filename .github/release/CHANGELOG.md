### Minecraft 26.3

Updated for Minecraft 26.3. This build targets 26.3 only — use 26.0.2 for
26.1.2 and 26.2.

### Particles

Enforcement is now visible. In `damage` mode a beacon fires a **tracer** up at
the player and throws **flak** around them; the other modes get a quieter puff
when flight is refused. All of it is spawned server-side, so everyone nearby
sees it and vanilla clients need no mod.

Every layer is configurable — on/off, particle type, count, spacing, jitter —
and can be turned off entirely.

### Configuration in game

- `/noflyzone config` — list every setting, read one, or change one, with
  tab-completion on both names and values.
- `/noflyzone reload` — re-read the config file without restarting the server.

Changes made either way take effect immediately and are written back to
`config/noflyzone.properties`.
