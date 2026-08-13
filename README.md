# taswell

A client-side Minecraft mod, built on Fabric, that takes over the game's ambient music
entirely. The soundtrack becomes the vanilla **C418** tracks — Volume Alpha and Beta,
plus the records — and whatever local **MP3/WAV** files you drop in a folder, unified
into one library and organized into playlists you control from an in-game player screen.
Named for the C418 track from *Volume Beta*.

Vanilla's own music scheduling is suppressed unconditionally once the mod loads, and
this mod's own `MusicDirector` becomes the sole source of ambient music — overworld,
Nether, creative, and the main menu alike. Scripted music (the End credits) is left
alone.

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | ≥ 0.19.x |
| Fabric API | 0.157.0+26.2 (or newer for 26.2) |
| Java | 25 |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.2.
2. Download the matching [Fabric API](https://modrinth.com/mod/fabric-api) jar for 26.2
   and drop it in your `mods/` folder.
3. Drop `taswell-<version>.jar` (see [Building from source](#building-from-source), or
   grab a release) in the same `mods/` folder.
4. Launch the game with the Fabric profile. Taswell is client-only — it does nothing on
   a dedicated server and is safe to leave out of a server's `mods/` folder.

## The music folder

Local tracks live in `config/taswell/music/` (created automatically on first launch if
missing). Drop `.mp3` or `.wav` files in there and either restart or hit the **⟳
Refresh** button in the player screen — there's no filesystem watcher, by design (a
button is honest; a poller isn't).

Title/artist are read from ID3 tags when present (via jaudiotagger); otherwise the
filename (minus extension) is used as the title. A file that fails to decode is logged
and skipped when its turn in rotation comes up — rotation always advances, it never
gets stuck on a bad file — but it still appears as an ordinary-looking row in the track
list; there's no visual marker yet distinguishing it from a playable one. You can still
click it directly (it'll just skip ahead again).

The folder path is changeable via `musicFolder` in `config/taswell/config.json` if
you'd rather point it somewhere else than the default (an in-UI control for this is
future work — the player screen doesn't have one yet).

## Playlists

The library is unified: vanilla C418 tracks and local files are peers of one `Track`
model, and playlists are just named selections over that whole library. There's no
separate "include my music" toggle — it's simply which playlist is active.

Three playlists are always available, synthesized from the catalog:

- **C418** — every vanilla track on the allowlist.
- **My Music** — everything found by the last folder scan.
- **Everything** — both, together.

You can also create your own named playlists from the sidebar (`+` to add, `✎` to
rename, 🗑 to delete — delete needs a confirming second click within a few seconds).
Editing one is **browse-with-toggles**: select a custom playlist in the sidebar and the
track list shows the *entire* catalog, with a toggle on each row to add or remove it
from that playlist. This is deliberate — showing only the playlist's own (possibly
still-empty) contents would leave no way to ever add a first track to a fresh playlist.
An entry whose underlying file has since vanished (e.g. a deleted local file) stays
visible, greyed out, rather than disappearing — a renamed or reorganized folder doesn't
silently wipe your curation.

The active playlist is what the ambient rotation plays from: shuffle draws randomly
without an immediate repeat, ordered mode walks the list in order, repeat-one loops the
current track, repeat-playlist wraps at the end. Clicking any track plays it immediately
as an interjection — an "on-demand" pick, not a mode switch — and rotation resumes from
the active playlist once it ends.

## Keybinds

| Key | Action |
| --- | --- |
| `M` | Open the player screen |
| `.` | Skip to the next track |
| `,` | Previous track (steps back one track if pressed early; restarts the current track if pressed again shortly after) |
| `-` | Play/pause |

All four are rebindable in Minecraft's own Controls screen, under the "taswell"
category.

The player screen itself also has on-screen transport (prev/play-pause/next, shuffle,
repeat cycle), a live search box filtering by title/artist/filename, and a volume slider
that's just Minecraft's own Music volume option — the same slider governs every track,
vanilla or local, since both register under the vanilla `MUSIC` sound category.

## Config file

Settings persist as human-readable JSON under `config/taswell/`:

- **`config.json`** — music folder path, min/max gap seconds between tracks, HUD toggle,
  active playlist id, shuffle flag, repeat mode.
- **`playlists.json`** — your custom playlists (the three synthesized builtins aren't
  stored here — they're derived from the catalog every time).

A config or playlists file that fails to parse (corrupted, hand-edited into invalid
JSON) is moved aside as a `.bad` sibling and the mod falls back to defaults — it never
boot-loops on a broken config file. A missing music folder is silently recreated empty
on next launch.

## The pause-restarts-track caveat

Minecraft's sound engine has no mid-stream pause/resume for a streamed instance, and
seek is out of scope for v1, so **pausing stops playback outright** and remembers which
track was playing; unpausing starts that same track over from the beginning rather than
resuming where it left off. This is a deliberate, documented limitation, not a bug —
there was no honest way to fake "resume" without seek support underneath it.

## Licensing — this mod ships zero audio

Taswell contains **no C418 or Mojang audio of any kind**. The vanilla C418 tracks are
referenced by the game's *existing* asset paths (the `.ogg` files the launcher already
downloaded as part of your Minecraft install) — the mod's own `sounds.json` registers
one sound event per allowlisted track pointing at that already-on-disk vanilla asset,
which is what lets a single C418 track sit in a playlist as a peer of a local MP3 in the
first place. No copyrighted audio is bundled, redistributed, or otherwise shipped in
this mod's jar. Local MP3/WAV files stay wherever you put them on your own disk and are
never touched by this repository.

The C418 catalog is carried as **data** (title → vanilla asset path) rather than code, and
is a closed, fixed allowlist — Mojang has been adding new composers to the vanilla
soundtrack for years (Raine, Tanioka, Cherof, Roddy, fingerspit, and more), so an
allowlist is what keeps the mod from ever accidentally scheduling non-C418 music, even
as the vanilla catalog grows.

Mod code (everything under `src/`) is licensed under the [MIT License](LICENSE). That
license covers the code only — it says nothing about, and grants no rights to, any
Mojang/Microsoft-owned game assets or C418's own audio, none of which this repository
contains.

## Building from source

Requires a JDK capable of running Gradle 9.5.1 (Loom provisions a Java 25 toolchain
automatically for compilation — you don't need Java 25 pre-installed, just something the
wrapper itself can launch with).

```sh
./gradlew build       # compile, run unit tests, produce the mod jar under build/libs/
./gradlew test        # unit tests only (plain JVM — no Minecraft classes involved)
./gradlew runClient   # launch a development client with the mod loaded
./gradlew genSources  # generate mapped (Mojmap) Minecraft sources for IDE navigation
```

The built jar lands at `build/libs/taswell-<mod_version>.jar`.

### Versions this was built against

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Fabric Loom | 1.17.19 |
| Gradle | 9.5.1 |
| Java | 25 |
| Mappings | Mojmap |

### Out of scope for v1

Seek bar, album art, streaming URLs/radio, FLAC/AAC, per-biome/situation playlist
triggers, a NeoForge port, and publishing to Modrinth — none of these are planned for
this release; see the design spec for the reasoning behind each.
