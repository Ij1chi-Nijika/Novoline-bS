# KeepSprint related Flux port

Source: the local Flux checkout (`minecraft_version=1.21.11`). Target: Raven Bs / Minecraft 1.8.9. This document describes the scope of the port, not a claim of server compatibility.

## Active chain

Enabling Keep Sprint selects the linked Flux attack/update behavior in KillAura, Velocity Prediction and Sprint. The six KeepSprint modes and existing user-selected settings remain available. Keep Sprint disabled leaves those modules on their existing Raven paths. Debug Trace identifies this revision with `FluxChain=2` in Forge's `logs/fml-client-latest.log`.

| Source behavior | Target integration |
| --- | --- |
| KeepSprint Vanilla / Prediction / Legit / Grim / Buffer / Packet | `movement/KeepSprint.java` |
| Tick resets, update state machine, living update | GameTickEvent, LeaderUpdateEvent at local player update HEAD, LivingUpdateEvent at movement update HEAD |
| Aura update and controller attack | KillAura regular attacks move from PrePlayerInteractEvent to LeaderUpdateEvent; `700 / CPS` timing; controller attack followed by swing |
| Aura attack events | Outer attack event plus controller event retained; controller sends attack and runs local attack code; all six modes use this path |
| Attack slowdown compensation | Local-player branch in MixinEntityPlayer, after vanilla reduction and sprint reset |
| Sprint key update | PreMotionEvent with forward/hunger/collision checks while linked; Raven's forced backwards/sideways/item sprint options do not override this path |
| Prediction velocity attacks | Real controller attack, then Flux's additional 0.6 horizontal reduction and optional sprint stop |
| Prediction velocity delay | Queue only local S12 velocity packets; release timing and AutoBlock phase gates; replay guard prevents recursively queuing the same packet |
| Velocity Blink | Separate outgoing queue after AutoBlock's queue, protocol/chat exclusions, KeepSprint active override and delayed slowdown on release |
| AutoBlock preparation | Flux Hypixel cycle calls `preparePredictionAutoBlock` when its attack stage permits it and target is within 3.5 blocks |
| AutoBlock reduction phases | Flux Custom and supported Lag timing tables; unsupported Flux Lag cases return false rather than being treated as ready |
| Velocity input jump | After movement input update, rather than being overwritten by vanilla input processing |
| World/module transitions | Queues clear on world unload and flush on module disable; queued velocity replay does not re-enter capture |

Velocity's source defaults differ from existing Raven profiles. Profiles are not silently rewritten to enable Velocity or change its reduce mode: select the desired settings explicitly. `Velocity -> Prediction -> Reduce mode: Blink` exposes the Blink timing controls. `KillAura -> Auto Block.Mode: WatchDog -> WatchDogMode: Flux Hypixel` selects the migrated preparation cycle. Packet KeepSprint does not require AutoBlock.

## Engine boundaries

The target still uses 1.8.9 movement, damage/knockback rules, sword blocking, rotation/target selection and wire protocol. It does not send 1.21.11 PlayerInput packets, implement its attack-cooldown system, or translate the entire game to that version. Sprint transition packets continue through the native 1.8.9 movement sender; no arbitrary extra sprint packets are injected.

Equal-priority Flux listeners follow module registration order. The linked player-update listeners now share one event with normal priority, but the host's other listeners remain Forge listeners. Raven's legacy AutoBlock modes are host integrations, not complete copies of every Flux AutoBlock/NoSlow mode. The existing Flux Hypixel cycle supplies KeepSprint preparation; unrelated standalone AutoBlock controls and KoiPrediction are outside this port.

Therefore matching the module code cannot establish identical server behavior across these two versions. Previous diagnostic logs reproduced position corrections with Velocity, NoSlow and AutoBlock disabled; that remains unresolved until the new build is tested. Packet mode deliberately removes attack slowdown and is not a server acknowledgement mechanism.

## Verification

`gradle build --offline` includes `testKeepSprintLinkage`. The deterministic checks cover FIFO Blink release, compensation before release, lifecycle clearing, Hypixel stages, supported Velocity/AutoBlock phase tables, release timing boundaries, and legacy profile migration. Compile-time Mixin remapping validates the velocity injection target; it is placed after PacketThreadUtil's main-thread handoff to avoid processing one packet twice. These checks do not simulate a multiplayer server.
