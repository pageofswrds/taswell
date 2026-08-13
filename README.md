# taswell

A client-side Minecraft mod for 26.2, built on Fabric.

## Development

Requires a JDK capable of running Gradle 9.5.1 (a Java 25 toolchain is provisioned automatically for compilation).

- `./gradlew build` — compile and run checks.
- `./gradlew runClient` — launch a development client with the mod loaded.
- `./gradlew genSources` — generate mapped (Mojmap) Minecraft sources for IDE navigation.

## Ambient rotation

Taswell owns all ambient music once the mod loads — vanilla's own music scheduling is suppressed unconditionally, and the director plays from the active playlist (vanilla C418 tracks and/or local mp3/wav files) with a randomized gap between tracks.

**Pause restarts the track.** Minecraft's sound engine has no mid-stream pause/resume for a streamed instance, and seek is out of scope for v1, so pausing stops playback outright and remembers which track was playing; unpausing starts that same track over from the beginning rather than resuming where it left off. This is a deliberate, documented limitation, not a bug.

## Versions

| Component | Version |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.157.0+26.2 |
| Fabric Loom | 1.17.19 |
| Gradle | 9.5.1 |
| Java | 25 |
| Mappings | Mojmap |
