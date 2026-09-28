# Catalyst

Finds out why a Minecraft server is lagging, tunes its config, and measures how much more
it can take. Runs on Paper, Purpur, Folia, Spigot and CraftBukkit (api-version 1.16), and
needs Java 21 on the server.

Every config file Catalyst changes is backed up first, and `/catalyst config restore`
puts it back.

## Commands

All commands need `catalyst.admin` (ops have it by default).

### Finding lag

| Command | What it does |
|---|---|
| `/catalyst perf profile [seconds] [-upload]` | Profiles every thread with spark, then names the kinds of work, plugins and methods using the tick. |
| `/catalyst perf plugins [seconds] [-upload]` | Profiles with spark, then lists the plugins using the most tick time, each broken down into its worst-performing event listeners. |
| `/catalyst perf profile cancel` | Stops a running `perf profile`, `perf plugins` or benchmark profile at once: nothing is reported for it, and a benchmark carries on without its profile. |
| `/catalyst perf stats` | Instant read of TPS, tick times, CPU and garbage collection, with what the pattern suggests (spikes vs. steady load, GC pauses, another process using the CPU). |
| `/catalyst perf lag [seconds]` | Counts block physics, liquid flow and redstone per chunk, lists the busiest chunks with a clickable teleport, and draws a chart. |
| `/catalyst perf lag cancel` | Stops a running `perf lag` sample at once; nothing is reported for it. |
| `/catalyst perf tps` | Current tick rate, and the TPS watchdog's state. |
| `/catalyst perf bench [low\|medium\|harsh\|extreme] [force]` | Measures what villagers, mobs, redstone, slime pistons, hoppers, dropped items, minecarts, explosions, new chunks of real terrain and players cost *this* server, in throwaway worlds, then ranks the likely causes of lag in your real worlds and suggests the obvious fix for each. The level sets how much load each stage adds (default `benchmark.intensity`, MEDIUM). See [Benchmark](#benchmark). |
| `/catalyst perf bench bots [count]` | Quick real-client test: just the empty world, the player footprint and `count` bots, without needing `benchmark.bots.enabled`. |
| `/catalyst perf bench bots [count] here` | Real clients join the world you are in (from the console, around its spawn) and roam it: walking, climbing and jumping on routes Catalyst plans through the terrain, kept from changing anything (no trampling, pressure plates, sculk or pickups; mobs ignore them; they never die). Measured against your world as it was just before. |
| `/catalyst perf bench bots [count] spread` | The same, but each bot roams a spot of its own scattered around you, as real players are spread out, rather than all bots sharing your chunks. |
| `/catalyst perf bench skip` | Skips the benchmark stage running now and moves on to the next; the skipped stage is left out of the report. Also offered as a clickable link beside the running stage. |
| `/catalyst perf bench cancel` | Stops a running benchmark: its bots leave, its worlds are removed, and nothing is reported. |
| `/catalyst perf spark install` | Downloads the latest spark for this platform (Spigot, or Folia's own build) and loads it. Needs `catalyst.spark.install` (ops have it). |

`-upload` sends the report to the endpoint set under `experimental.upload` in `config.yml` and gives you only the link in chat, instead of printing the whole report. It is only offered once upload is set up.

### Config tuning

| Command | What it does |
|---|---|
| `/catalyst config check [-upload]` | Scans the server's config files and explains every suggestion. |
| `/catalyst config apply safe` | Applies the changes with no gameplay downside. |
| `/catalyst config apply moderate` | Also applies changes with small trade-offs. |
| `/catalyst config backups` | Lists saved backups. |
| `/catalyst config restore <number>` | Rolls back to a backup. |

### Network

| Command | What it does |
|---|---|
| `/catalyst network info` | Player pings and packet settings. |
| `/catalyst network pipeline [player]` | Which plugins have injected handlers into a connection. |
| `/catalyst network profile [seconds] [-upload]` | Times each handler on your own connection. |
| `/catalyst network tps [seconds]` | How late the network threads run the work they are given: the delay every packet on them waits, per thread, also shown on the TPS scale. |

### Other

| Command | What it does |
|---|---|
| `/catalyst modules` | The runtime modules (mob limiter, item merger) and what they have done. |
| `/catalyst modules <module> <on\|off>` | Turns a runtime module on or off and saves it to `config.yml`, keeping its comments. |
| `/catalyst status` | Platform, modules and the last scan. |
| `/catalyst reload` | Reloads the config and runtime modules. |

Hovering over any clickable text shows exactly what a click runs or fills in. Commands that
change things are never run by clicking in chat: the click puts them in your
chat bar so you can read them first. During a benchmark, a player running it also gets
teleport links to watch the test area and the bots, and is sent back afterwards.

## Safe and moderate changes

**Safe** changes are well established and have no gameplay effect players would notice:

- Spawn limits and ticks per spawn
- Simulation distance
- Paper entity behaviour (collision limits)
- Explosion optimization
- Pathfinding on block update
- Grass spread rate
- User cache save timing

**Moderate** changes are effective but have a trade-off. Read `/catalyst config check`
before applying them:

- **Entity activation range**: entities far from players run less AI, so distant farms slow down.
- **Item and XP merge radius**: drops combine sooner, so they can visibly jump.
- **Network compression**: less CPU spent compressing, more bandwidth used.
- **Hopper occlusion check**: hoppers ignore containers hidden inside solid blocks.
- **Redstone implementation** (Alternate Current): much faster, but a few timing-sensitive contraptions behave differently.
- **Mob spawner tick rate**: spawners check less often, so spawner farms produce a little less.
- **Tick inactive villagers**: villagers far from players stop working until someone is near.
- **View distance**: players see fewer chunks.

## Benchmark

`/catalyst perf bench` creates a flat world, adds one kind of load at a time, measures the
tick-time difference against the empty world, and deletes its worlds afterwards. Each stage
runs three times and the median is reported. The stages are villagers, cows, redstone clocks,
sticky pistons lifting slime blocks, hopper loops passing items round, dropped items that cannot merge or be picked up, minecarts going round rail loops, TNT and TNT minecarts exploding,
the chunks a player keeps ticking, and optionally real clients. Each stage lists an amount for LOW, MEDIUM, HARSH
and EXTREME under `benchmark.stages` (each can be switched off with `enabled: false`); `benchmark.intensity` picks the level, or
`/catalyst perf bench harsh` for one run. The clocks, pistons, hoppers, items and minecarts each check they really ran
(pulsing, moving their slime, passing items, still lying there, still going round) and the report says if some did not.
In the bench world, mobs are kept fully active wherever they are (entity activation range is
lifted for that world only), so their figures are what they cost near a player. The benchmark
wants 1 GB of free disk space before it starts.

Chunk generation is timed in real terrain, not the flat world, whose chunks cost a fraction
of real ones. Catalyst makes a second temporary world with your real world's seed and
generator and generates new chunks there, far from anything built. Nothing in your real world
is loaded or changed. The real world is the overworld holding the most generated land, going
by its region files, so a void or flat hub set as the default world is never picked over the
survival world. Name a world in `benchmark.terrain-world` to choose it yourself. The report
says which world was used and why.

The report ends with **recommendations**. Catalyst counts the villagers, mobs, players,
loaded chunks, hoppers, dropped items and minecarts in your real worlds, multiplies each by
what the benchmark measured, ranks the results, and pairs each with the pending Catalyst change
that addresses it (or plain advice if none does). Redstone and explosions cannot be counted in
advance, so they are listed with their measured price when there is a pending fix for them, and
slow terrain generation with advice to pregenerate. Load it cannot explain points you to
`perf profile`.

Results are saved to `plugins/Catalyst/reports/bench-<date>.txt`, with every individual run,
and uploaded if upload is configured.

The benchmark worlds are called `catalyst_bench` and `catalyst_bench_terrain`, and each carries
a marker file saying Catalyst made it; only a folder with that marker is ever deleted. If a
world by either name already exists and Catalyst did not make it, the benchmark refuses to run
rather than touch it. Anyone inside a benchmark world when it closes, an admin watching for
example, is sent back to where they entered it from, or else to their own respawn point, or
else to the overworld spawn, as the game would respawn them. The same happens to anyone who
logs out inside, so their next login does not put them at those coordinates in the overworld,
underground. Nobody is kicked.

### Real clients (optional)

With `benchmark.bots.enabled: true`, or on demand with `/catalyst perf bench bots [count]`,
the benchmark also connects real Minecraft clients ("bots") to show what a player actually
costs, next to the cheaper footprint estimate. In testing, how far the two are apart varied a
lot between server software and versions, which is why it is worth measuring on
your own server.

The bots:

- connect to `127.0.0.1` only, on this server's port. There is no setting for an address.
- are limited to 50.
- are named `cat_bot_1`, `cat_bot_2`, ... (or another prefix, `benchmark.bots.name-prefix`). Catalyst refuses to run them if a real player has
  used one of those names on the server (in offline mode the bot would share their data; in
  online mode plugins that go by name could mistake the bot for them). In online mode it also
  asks Mojang who owns those names, since the server only remembers its most recent players,
  and refuses if it cannot find out.
- run in a separate Java process, so their own CPU use is not counted as server load.
- spawn in the benchmark world, each at the head of a lane of its own wider than its view, and
  walk along it so new terrain keeps loading; no two bots ever load the same chunks. They leave
  when the stage ends. With `here`, they roam your real world instead (see the command above),
  within 24 blocks of where you stood, taking knockback like players. With `spread`, each bot
  instead gets a spot of its own scattered up to `benchmark.bots.spread-radius` (256) blocks
  away, on land already generated, and roams within 24 blocks of that.
- on Paper, have every new chunk they cause also generated at the same spot in the
  real-terrain world, so the figure includes what exploring real terrain costs. Spigot has no way
  to do that without stalling the server thread, which no real player causes, so there the bots
  explore flat terrain and the report says so.
- leave nothing behind: each bot's player data is deleted as it leaves, the bot process's
  working files are removed afterwards (its log is kept only if the run failed), and Catalyst
  lets go of everything the run held in memory.
- get past a whitelist, bans, a full server and kicks from other plugins, but only under a
  bot's name, connecting from `127.0.0.1` with the run's token while the stage runs, so a real player using a bot
  name gets no such pass.
- carry the metadata `CatalystBot` while online (`player.hasMetadata("CatalystBot")`), so
  other plugins can tell them apart. The key is set in `benchmark.bots.metadata-key`, and
  the metadata is removed as each bot leaves.
- on Paper, wear the skins of players listed in `benchmark.bots.skins`, one at random each,
  purely for looks. The default list is Mojang developers plus Mojang's Steve, Alex and
  Herobrine accounts; the skins are fetched from Mojang at the start of a run. Set it to `[]`
  to fetch nothing. Elsewhere the bots keep Minecraft's default skins.
- prove who they are with a random token made for each run. If someone else logs in under a
  bot's name during a run, Catalyst gives that name up: it never kicks them or deletes that
  name's data, and says so in the console.
- use the newest protocol, translated to the server's version by ViaVersion and ViaBackwards
  where the versions differ. Tested on Paper 1.20.4, 1.21.11, 26.2 and 26.3, Purpur 1.21.11,
  Spigot 1.21.11 and CraftBukkit 1.21.11.

With spark on the server (Paper bundles it), the bot stage ends with 20 more seconds of
profiling while the bots are still on, taken after the measurements so it cannot affect them.
The report then lists anything specific the run found, and only when it is clear: a plugin
using at least 0.25ms of every tick with the bots on, named with its costliest event listeners;
chunk loading and generation taking a large share of the tick; or terrain generation falling
behind the exploring bots. When nothing stands out, it says so. The profile itself is kept in
`plugins/spark/`, to open at spark.lucko.me for the full picture.

**The bots are a measuring tool, not a way to fake a player count.** Please don't use them to
make a server look busier than it is. It is dishonest to the people deciding whether to join,
and server lists treat it as grounds for removal. It is also about the least efficient way to
do it: every bot is a full client, with its own connection, chunks and entity ticking on your
server, which is exactly the load this stage exists to measure. Catalyst makes it impractical
anyway: bots only connect while a benchmark runs, at most 50, under numbered bot names,
with metadata that marks them as bots, and they leave when the stage ends.

The bots need about 19 MB of libraries, which Catalyst downloads into `plugins/Catalyst/libs`
from Maven Central, repo.opencollab.dev and repo.viaversion.com. Each file is checked
against a SHA-256 built into the plugin, and any file that does not match is deleted and
never used. Set `libraries.download: false` on hosts without internet access.

By default ViaVersion, ViaBackwards and ViaLoader are pinned to the versions this release
was tested with. `libraries.via: latest` fetches their newest release at startup instead, so
a new Minecraft version works without waiting for a Catalyst update. Those jars can only be
checked against the SHA-256 their repository publishes, which catches a broken download but
not a compromised repository, so it is off by default.

## Usage statistics

Catalyst reports anonymous usage statistics to [bStats](https://bstats.org/plugin/bukkit/CatalystPerformance/34291):
server count, Minecraft version and platform, and which features are on (the runtime modules,
whether benchmark bots are enabled). Nothing identifies a server or its players. Turn it off
with `metrics: false` in `config.yml`, or for every plugin at once in `plugins/bStats/config.yml`.

## Integrity check

Every build stores a SHA-256 fingerprint of the jar's contents inside the jar. At startup
Catalyst recomputes it before doing anything else, and if the jar was changed after it was
built, it logs a security alert and disables itself. If you rebuild the jar yourself, turn it
off with `security.verify-jar-integrity: false`. The check is TotemGuard's, used under
its GPL-3.0 licence.

## Honest limits

- **Bots work on offline-mode servers, online-mode Paper 1.20.5+, and proxy backends.**
  On an online-mode server the login is normally verified with Mojang, which bots (having no
  accounts) cannot pass. On Paper 1.20.5 and later, Catalyst lets a connection skip that check
  only when it comes from `127.0.0.1`, carries the stage's random token in its handshake (the
  same proof every bot must give anyway) and logs in as one of that stage's bot names.
  For those it drives Paper's own offline-login path; every other connection still
  authenticates normally, and the hook is removed when the stage ends. This is the one place
  Catalyst touches authentication, and it is off unless the server is in online mode. Older
  Paper and other server software (Spigot, CraftBukkit) expose no such hook, so there the bots
  need offline mode. On a BungeeCord or Velocity backend the bots supply the forwarding data
  the proxy normally adds (for Velocity, signed with the secret from the server's own config),
  still only over `127.0.0.1` and only under the bots' names.
- **In a container, bots share the server's memory and CPU limits.** Catalyst refuses to
  launch them unless the container's memory limit has room for them beyond the most the server
  itself can grow to (its `-Xmx` plus Java's overhead), since going over the limit gets the
  server killed. On hosts where the panel's memory setting is `-Xmx`, lower it a little to make
  room. While they run, they are stopped early if the heap passes `benchmark.bots.memory-limit`
  or the container passes 95%. It also refuses with fewer than 2 CPU cores.
- **Java keeps the memory it has grown to.** After a bot run a panel can show the server near
  its limit although the bots are gone; that is heap Java reserved and will reuse, not a leak.
- **`perf profile` and `perf stats` need spark.** Paper bundles it. Elsewhere,
  `/catalyst perf spark install` downloads the latest build and loads it without a restart:
  on Spigot from spark's own download API, checked against the SHA-1 it publishes; on Folia,
  which switches its bundled copy off, the Folia build from
  [spark-extra-platforms](https://ci.lucko.me/job/spark-extra-platforms/), whose builds come
  with no hash, so only HTTPS vouches for that download. On plain CraftBukkit, spark 1.10.187
  fails to start because of a spark bug, and the command removes it again. Without spark,
  `perf profile` on Folia lists the busiest chunks near players instead, and `perf lag`,
  `perf tps` and the benchmark work anyway. spark only times ticks on Paper and its non-Folia
  forks, so on Spigot and CraftBukkit `perf stats` judges from TPS, CPU and GC.
- **On Folia, TPS is per region.** Folia has no single server tick: each area of loaded chunks
  ticks on its own. `perf tps`, `perf stats`, `perf profile` and the TPS watchdog read each
  region's own tick times from Folia and report the slowest region, with a link to teleport
  there, since an average would hide one laggy farm among idle areas. With no chunks loaded,
  nothing is ticking, and the reports say so.
- **On Folia, reports only see chunks near players.** Folia only lets a chunk be read by the
  region thread that owns it, so `perf profile` checks the chunks around each online player
  from that player's own region. A farm or chunk loader with nobody near it is not seen. The
  benchmark cannot run on Folia at all, because Folia cannot create worlds while running.
- **On Spigot and CraftBukkit, an empty server pauses.** Since 1.21.2 vanilla stops ticking
  worlds once nobody has been online for `pause-when-empty-seconds` (60 by default; Paper turns
  it off). A paused server has nothing to measure, so the benchmark refuses to start on an
  empty server with the pause on. Join and stay online while it runs, or set it to `-1`.
- **Plain CraftBukkit has no TPS API.** Catalyst measures TPS itself there, which takes about
  a minute to warm up after startup.
- **Benchmark estimates assume load scales linearly.** Treat "room for ~N more" as a ceiling,
  not a promise.

## Building

Needs JDK 21. Gradle is held at 8.14 on purpose (IntelliJ 2024.1 cannot read Gradle 9 build
scripts).

```
./gradlew build
```

The plugin jar is `build/libs/Catalyst-<version>.jar`. `build` also runs the unit tests.

## Licence

GPL-3.0-or-later; see [LICENSE](LICENSE). Catalyst uses the Via libraries at runtime, which
are GPL-3.0, and includes the jar integrity check from
[TotemGuard](https://github.com/Bram1903/TotemGuard) (GPL-3.0).
