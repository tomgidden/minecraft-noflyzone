# No-Fly Zone — design notes

Why this mod stores its state in a beacon's NBT instead of registering a
`MobEffect`, and what the `MobEffect` version would have looked like.

This is a record of a decision, not a to-do list. The approach described in
[The mobeffect approach](#the-mobeffect-approach) is **not implemented and will
not be**. It is documented because it is the approach the vanilla beacon code's
own conventions point at, and because the reason it fails is not obvious from
reading either vanilla or this mod.

---

## The requirement that decides everything

> Enforcement must be server-side and work on completely unmodified clients.
> Configuring a beacon may require a client mod.

Every other design question follows from this. A player who has never heard of
this mod, connecting with a stock client, must be grounded inside a zone.

That requirement is what rules out the mobeffect approach, and nothing else
does. If this mod were allowed to require the client, the mobeffect approach
would be the better of the two.

---

## The two approaches

|                        | **mobeffect** | **beacon-nbt** (implemented) |
| ---------------------- | ------------- | ---------------------------- |
| Zone state lives in    | A registered `MobEffect`, stored as the beacon's primary/secondary power | A boolean in the beacon block entity's NBT |
| Client learns of it by | Vanilla's entity-effect sync | A custom `noflyzone:in_zone` payload |
| Selection travels as   | `ServerboundSetBeaconPacket` (vanilla) | `noflyzone:set_zone` (custom) |
| Validation done by     | Vanilla's `validateEffects` | This mod's `SetZoneHandler` |
| Unmodded clients       | **Disconnected at login** | Fully affected, no icon |
| `/effect`, predicates  | Work | Not available |
| Duration countdown     | Real | None (icon is indefinite) |

"beacon-nbt" is shorthand for *block entity* NBT — it has nothing to do with the
vanilla `BlockState` class.

---

## Why the mobeffect approach fails

`MobEffect` is not a synced registry.

- `BuiltInRegistries.MOB_EFFECT` is created with `registerSimple`.
- It is absent from `RegistryDataLoader.SYNCHRONIZED_REGISTRIES`.
- It is hard-frozen after `Bootstrap.bootStrap()`.

Vanilla has no mechanism for a server to teach a connecting client about a new
effect. Effects cross the wire as **bare numeric ids** — `MobEffect.STREAM_CODEC`
is `ByteBufCodecs.holderRegistry`, a VarInt resolved with `byIdOrThrow` — so an
id the client doesn't have is a decode failure, not a graceful degradation.

Fabric API layers its own registry sync on top: `MappedRegistryMixin` sets
`RegistryAttribute.MODDED` the moment a non-vanilla entry is registered, which
forces the registry into the login sync payload. An unmodded client then fails
that sync and is disconnected:

```
RemapException: Registry entry (noflyzone:no_fly) is missing from local registry
  (minecraft:mob_effect)
```

There is no supported opt-out:

- `RegistryAttribute.OPTIONAL` covers a wholly **missing registry**, not missing
  **entries** within a present one.
- `removeAttribute` throws outside a development environment.

This was reached empirically, not by reading source. An earlier iteration of
this mod registered the effect server-side and worked correctly in singleplayer
and against modded clients — the failure only appears when a stock client
connects to a dedicated server.

**This is a property of the game, not of Fabric.** NeoForge's registry sync is
differently implemented but lands in the same place, because the underlying
problem is that vanilla's effect id encoding has no room for an unknown id.

### The singleplayer trap

Several parts of the mobeffect approach *appear* to work in singleplayer and
then do nothing on a real server, because singleplayer shares one JVM between
client and server. `BeaconEffectListMixin`'s doc comment records the same trap
for `BEACON_EFFECTS`. Any testing of registry- or screen-related behaviour has
to be done against a dedicated server with a genuinely unmodified client.

---

## The mobeffect approach

What it would look like, for reference. **Not implemented.**

### Common

| File | Role |
| ---- | ---- |
| `ModMobEffects` | `DeferredRegister`-equivalent registration of `noflyzone:no_fly` into `BuiltInRegistries.MOB_EFFECT`, on **both** sides. |
| `NoFlyEffect extends MobEffect` | The effect itself. Mechanically inert — enforcement still reads position, not effect state, since the effect is on the *player* and the zone is a property of the *beacon*. |
| `BeaconBlockEntityMixin` | Widens `VALID_EFFECTS` so vanilla's own validation accepts No-Fly, and reads `primaryPower`/`secondaryPower` in `applyEffects` instead of a separate flag. No NBT work at all — vanilla already persists the beacon's chosen effects. |
| `BeaconEffectListMixin` | Appends No-Fly to `BEACON_EFFECTS`, applied on **both** sides (server for validation, client for the buttons). |

No `NoFlyBeacon`, no `ModPayloads`, no `SetZoneHandler`, no `ZoneStateNotifier`,
no `ClientPayloadSender`, no `BeaconConfirmMixin`. Roughly six files fewer.

Enforcement (`NoFlyPolicy`, `NoFlyZones`, `LivingEntityCanGlideMixin`,
`TridentItemMixin`, `FireworkRocketItemMixin`) is **identical** in both
approaches — it never depended on how the beacon's state was represented.

### Per loader

| Loader | What changes |
| ------ | ------------ |
| `fabric-mobeffect` | `NoFlyZoneFabric` registers the effect during `onInitialize`. No payload registration. `NoFlyZoneFabricClient` shrinks to almost nothing — no receiver, no sender shim — since the icon arrives via vanilla effect sync. |
| `neoforge-mobeffect` | Same, via `DeferredRegister<MobEffect>` on the mod event bus. No `RegisterPayloadHandlersEvent`. |

### Why it's cleaner where it works

- No custom protocol, so no security boundary of our own — vanilla's
  `validateEffects` does the tier and payment checks it already does for every
  other beacon effect.
- No `@Redirect` on `BeaconConfirmButton.onPress`; the vanilla packet carries the
  selection.
- No edge-triggered notifier and no per-tick position check for HUD purposes.
- `/effect`, `@a[nbt=…]`, predicates and other mods' `hasEffect()` all work.
- The icon shows a real duration.

All of which is why it's the shape the beacon code implies. It just cannot
satisfy the requirement at the top of this document.

---

## The beacon-nbt approach

What is actually implemented.

### State and persistence

| File | Role |
| ---- | ---- |
| `NoFlyBeacon` | Duck-type interface mixed into `BeaconBlockEntity`. One boolean, plus the NBT key it persists under. |
| `BeaconBlockEntityMixin` | Holds the flag; persists it in `saveAdditional`/`loadAdditional`; publishes the zone from `applyEffects` and cancels the beacon's normal potion effect. |

Hooking `applyEffects` rather than the beacon's tick is deliberate: vanilla only
reaches it for a beacon it already considers fully active, so pyramid tier and
sky access are inherited for free, including deactivation when someone roofs a
beacon over.

### Zone registry and enforcement

| File | Role |
| ---- | ---- |
| `NoFlyZones` | Per-dimension `Map<BlockPos, Zone>` of active zones; the `isInZone` query; self-cleaning expiry (200 ticks) so a beacon that stops publishing ages out without needing every deactivation path hooked. |
| `NoFlyPolicy` | Whether to refuse, what each mode does in flight, plus the action-bar message and its per-player cooldown. No bypasses — operators and creative mode included. |
| `NoFlyMode` | The three enforcement modes, and parsing them from config or command. |
| `LivingEntityCanGlideMixin` | Refuses gliding, for the modes that stop it. `canGlide()` covers both takeoff (`Player.tryToStartFallFlying`) and per-tick flight (`LivingEntity.updateFallFlying`). |
| `LivingEntityGlideTickMixin` | Per-tick effects for the modes where the player *keeps* flying — the velocity cap and the damage. Injected at `TAIL` of `updateFallFlying`, so vanilla's own movement has already been applied. |
| `TridentItemMixin` | Refuses riptide launches. |
| `FireworkRocketItemMixin` | Refuses firework boosts while gliding. |

### Configuration protocol

| File | Role |
| ---- | ---- |
| `ModPayloads` | `noflyzone:set_zone` (serverbound, one boolean) and `noflyzone:in_zone` (clientbound, one boolean). Neither mentions a registry id — that is what keeps unmodded clients connectable. |
| `ZoneMutation` | **The single place a beacon's flag is validated and changed.** Owns eligibility (tier, already-in-state) and the mutation itself. |
| `SetZoneHandler` | **The security boundary** for the client-mod route. Vanilla's validation is bypassed entirely, so open-menu, `stillValid` (reach + block identity) and payment are all re-checked here against a payload a modified client controls, before delegating to `ZoneMutation`. |
| `NoFlyCommand` | `/noflyzone on\|off\|status [radius]`, the operator route. Authorises by permission instead of an open menu, then delegates to the same `ZoneMutation`. |
| `BeaconLevelsAccessor` | `@Accessor` for the private `BeaconBlockEntity.levels`, since the command has no menu to read the tier from. |
| `BeaconMenuAccessor` | `@Accessor` for `BeaconMenu.access`, the server-side `ContainerLevelAccess` used to resolve the beacon from the player's open menu rather than trusting a client-supplied position. |
| `ZoneStateNotifier` | Edge-triggered `in_zone` pushes. Cosmetic only; enforcement never consults it. |

`SetZonePayload` deliberately carries **no position**: the client screen cannot
know the beacon's coordinates, since `ContainerLevelAccess` is server-side only,
and a client-supplied position would be untrustworthy regardless.

#### Two routes, one set of rules

The client-mod route and the command route authorise differently — one by having
a valid beacon menu open, the other by permission level — but must agree
exactly on what makes a beacon eligible and on how the change is applied. That
split is why `ZoneMutation` exists separately from both callers: **authorisation
belongs to the route, eligibility and mutation belong to the beacon.**

`SetZoneHandler` additionally pre-checks the tier against the menu before
consuming payment, so a doomed request doesn't cost an ingot; `ZoneMutation`
re-reads the tier from the block entity, which is authoritative.

#### Why enforcement needs two hooks

Originally `canGlide()` was the whole of it: return false and both takeoff and
sustained flight stop. That remains true for `no-glide`.

The other two modes need the player to *keep gliding* — `zero-momentum` so they
descend safely rather than drop, `damage` so they keep control while being hurt.
Neither can be expressed as a refusal, so they act per-tick on a player vanilla
still considers airborne. Hence the split: `canGlide` decides *whether* flight is
allowed, `updateFallFlying` shapes what flight is *like*.

`zero-momentum` is the awkward one, because it refuses takeoff but permits an
existing glide. Both answers come out of the same method, distinguished by
`isFallFlying()`.

### Server-side translation

| File | Role |
| ---- | ---- |
| `NoFlyMessages` | Loads the mod's own language files server-side and resolves each message in the recipient's language. |

`Component.translatable` is resolved by the *client*, against language files the
client holds — so an unmodded player sees `noflyzone.message.takeoff_denied`
verbatim at the moment their elytra stop working.

`translatableWithFallback` carries a literal string alongside the key. The
fallback is chosen **per send, on the server**, and nothing requires it to be
English: the server reads `ServerPlayer.clientInformation().language()` and looks
the key up in its own copy of the shipped `.json` files. One call then serves
both audiences — a modded client translates the key locally and honours resource
packs, an unmodded one renders a fallback already in its own language.

Note this is why `format()` implements `%s` and `%1$s` substitution by hand: a
fallback is rendered as-is by the client, so the server must do the substituting.
The indexed form is not optional — `en_ud` reverses whole strings, and vanilla's
own `en_ud` uses `%1$s` for exactly that reason.

### Client half (optional)

| File | Role |
| ---- | ---- |
| `ClientNoFly` | Registers the effect in the **client's own** registry; appends it to `BEACON_EFFECTS`; applies/clears it locally on `in_zone`. |
| `ClientNoFlyEffect` | The effect. Mechanically inert — it exists to give the screen a button and the HUD an icon. |
| `ClientPayloadSender` | Loader-agnostic send shim. |
| `BeaconConfirmMixin` | `@Redirect` on `BeaconScreen$BeaconConfirmButton.onPress`, substituting `set_zone` for the vanilla packet when the selection includes No-Fly. Any other selection passes through untouched. |
| `BeaconEffectListMixin` | `@Accessor` pair for `BEACON_EFFECTS`. **Client-only** — mutating the server's copy achieves nothing, since `BeaconScreen.init()` reads the client's. |

Registering the effect client-side only is safe precisely because the id never
crosses the network: the selection travels as a boolean, and so does the HUD
state.

### Per loader

| Loader | Entrypoints |
| ------ | ----------- |
| `fabric-beacon-nbt` | `NoFlyZoneFabric` — `PayloadTypeRegistry` registration, `ServerPlayNetworking` receiver, tick and lifecycle events, sender shim guarded by `canSend`. `NoFlyZoneFabricClient` — client registration, `ClientPlayNetworking` receiver (registering it is also what declares the channel). |
| `neoforge-beacon-nbt` | `NoFlyZoneNeoForge` — `RegisterPayloadHandlersEvent` with `.optional()` (without it NeoForge refuses unmodded connections, which is the whole point), sender guarded by `hasChannel`. `NoFlyZoneNeoForgeClient` — `FMLClientSetupEvent`, guarded so client-only classes never link on a dedicated server. |

---

## Consequences accepted

- **No command or datapack interop.** Nothing can test for no-fly state via
  `/execute if entity`, predicates, or NBT selectors. A scoreboard objective
  would be the workaround if this is ever wanted. This is the only consequence
  with no mitigation.
- **The HUD icon shows no duration**, since the local effect is applied
  indefinitely and cleared on exit.
- **Other mods can't see it** — server-side `hasEffect()` returns nothing.
- **The icon is one hop behind**, arriving via a server tick check rather than
  vanilla's effect sync. Edge-triggered, so the cost is two packets per zone
  transit.
- **An orphan NBT flag** persists on beacons if the mod is removed. Harmless and
  ignored by vanilla; reinstating the mod restores the zones.
- **Deactivation isn't instant.** Vanilla re-evaluates a beacon every 80 ticks
  and zones expire after 200, so roofing a beacon over takes up to ~10 seconds
  to take effect. Explicit deactivation via `set_zone` is immediate.

---

## Version compatibility

The mod targets Minecraft ≥26, <27, and is built against **26.2** while
remaining runtime-compatible with 26.1.x.

The reason to build against the newer version: `BeaconBlockEntity.validateEffects`
exists in 26.2 but **not** in 26.1.2, and in 26.2 a validation failure
disconnects the player. Compiling against 26.1.2 and using `require = 0` to
tolerate the missing method silently drops the injection — `require = 0`
suppresses both the failure *and* the signal that it failed. Building against
26.2 binds it properly; the mixin targets used here were verified as a strict
superset across both versions with `javap`.

Note that the current beacon-nbt implementation does not in fact need
`validateEffects` at all, since the selection bypasses vanilla's packet
entirely. The 26.2 build target remains because the mixin surface is verified
against it.
