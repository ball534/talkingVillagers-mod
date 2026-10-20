# AGENTS.md

Fabric mod for Minecraft 26.3 (Java 25): a server-side villager overhaul that gives villagers
names, personalities, relationships, families and village politics, and lets players talk to
them in their own words through a local [Ollama](https://ollama.com) model. Vanilla clients can
join with no mods installed. Entry point:
`src/main/java/com/talkingvillagers/TalkingVillagers.java`. Behavior spec: `README.md`;
porting notes: `docs/mc-26.2-api-notes.md`.

## Ground rules

- **Every new feature goes through a PR** — never commit feature work directly to
  `main`, no matter how small. Fixes should go through PRs too.
- **Check the version before merging to `main`**: if a branch changes
  `mod_version=` in `gradle.properties`, the new number must be strictly *newer* than
  the one currently on `main` (`git show origin/main:gradle.properties`). Never
  merge a downgrade, and never merge a release with an unchanged version number.
- **`README.md` is written for Modrinth readers**: a release syncs it to the
  Modrinth project description, so it must read as if the mod was downloaded from
  Modrinth — describe in-game behavior only, no "drop the jar into your `mods`
  folder" install steps, and use absolute image URLs (e.g.
  `https://raw.githubusercontent.com/…`) so images also render on Modrinth.
- **Speech stays local.** Conversation content goes to the Ollama server the player runs
  themselves and nowhere else. Nothing may block the server thread on the model — when it
  is slow or missing, villagers go quiet.

## Workflow

- **Features go on new branches**, never on the main branch.
  Branch name: `feat/<short-name>` (fixes: `fix/<short-name>`).
- **Conventional commits**: `feat:`, `fix:`, `refactor:`, `docs:`, `chore:` —
  e.g. `feat: add vein mining for exposed ores`.
- **PRs** into `main`, one feature/fix per PR, PR title = conventional commit style.
- **Semantic versioning** in `gradle.properties` (`mod_version=…`), Minecraft-mod style:
  - **MAJOR** — new Minecraft version support / breaking behavior changes
  - **MINOR** — new features
  - **PATCH** — bug fixes
  Bump the version in the feature or fix commit that needs it. Tags (`v x.y.z`)
  go on `main` only when a version is actually released — never on feature branches,
  and always matching the `mod_version=` on the tagged commit. A release is published
  by creating the GitHub Release on that tag; the `Modrinth release` action ships it,
  using the release's generated notes as the Modrinth changelog.

## Before opening a PR

```
./gradlew build        # must pass
```

`README.md` documents player-facing behavior — update it when behavior changes.
