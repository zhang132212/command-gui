# CLAUDE.md

## Project Overview

**easy-cmd** (Command-GUI) is a Fabric Minecraft mod (Java 25) that provides a GUI for executing commands, managing fake players, and using a placeholder system. Targets Minecraft 26.2.

- Mod ID: `command-gui`
- Version: `0.1.0-beta.8`
- License: GPL-3.0

## Build & Run

```bash
# Build the mod
./gradlew build
# Output: build/libs/command-gui-<version>.jar

# Run Minecraft client in dev environment
./gradlew runClient
```

> **Dev setup notes:**
> - `runClient` enables `DevAuth-fabric` (`-Ddevauth.enabled=true`), so a real Minecraft account (or configured DevAuth) is required.
> - ModMenu is `modCompileOnly` — to test ModMenu integration locally, add it as `modLocalRuntime` in your own `build.gradle`.

## Project Structure

```
src/
  main/java/com/remrin/          # Server-side entry point (CommandGUI.java)
  client/java/com/remrin/client/ # CommandGUIClient.java, ModMenuIntegration.java
    config/                      # CommandConfig, PresetConfig, SettingsConfig
    gui/                         # All GUI screens and helpers
  main/resources/                # fabric.mod.json, lang files, presets, textures
  client/resources/              # Client mixin config
docs/                            # Bilingual documentation (10 .md files)
```

## Architecture

- **Entry points**: `CommandGUI.java` (server, minimal), `CommandGUIClient.java` (client, keybinds/ticks)
- **GUI base**: All screens extend `BaseParentedScreen<P>` or Minecraft's `Screen`
- **Main screen**: `CommandGUIScreen` → tabs: `CustomCommandTab`, `FakePlayerTab`, `PresetCommandTab`
- **Placeholder system**: `ChainedCommandExecutor` resolves 8 placeholder types — `{player}`, `{player_all}`, `{player_fake}`, `{name}`, `{number}`, `{time}`, `{coords}`, `{x}` — via sequential input screens (`{y}` and `{z}` are resolved alongside `{x}` as a single COORDS input)
- **Config**: JSON files via Gson under `config/command-gui/`

## Code Conventions

- Java 21, no external libraries beyond Fabric API and Minecraft
- Tab indentation
- Standard Java naming (PascalCase classes, camelCase methods)
- JavaDoc comments on all classes
- Bilingual UI strings (English + Simplified Chinese via `lang/` files)

## Commit Convention

Uses conventional commits (configured in `cliff.toml`):
- `feat:` — new features
- `fix:` — bug fixes
- `perf:` — performance improvements
- `refactor:` — code refactoring
- `docs:` — documentation
- `chore:` — build/maintenance

# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
