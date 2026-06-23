# Contributing

Thanks for your interest. The project is early — APIs and save formats may still shift — but contributions are welcome.

Real-time chat, questions, and showcases: **[Discord](https://discord.gg/QtgrdA6Rje)**.

## Reporting bugs / requesting features

Open an issue using one of the templates:

- **Bug report** — for crashes, incorrect behavior, broken integrations.
- **Feature request** — for new nodes, editor capabilities, or mod compat.

Versions of NeoForge / Nodewire / relevant mods help a lot. Logs / crash reports help even more.

## Submitting code

1. **Discuss first.** For anything beyond a small fix, open an issue (or comment on an existing one) before starting. Saves both of us from a wasted PR.
2. **Follow existing patterns.** Read neighbouring files before introducing a new abstraction. The UI framework's three-layer modifier pattern, `EditorState` flow-based ops, codec layout, and `NodeType.Builder` style are intentional — match them.
3. **Tests.** Add unit tests where reasonable. Existing coverage lives under `src/test/kotlin/`. Run locally:
   ```bash
   ./gradlew test
   ```
   CI doesn't run tests yet (ModDevGradle + signed-jar quirk — see README). Local green tests before pushing is the discipline.
4. **Commit messages.** Conventional-style prefixes (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `ci:`) — keeps history greppable. One change per commit.
5. **One PR per concern.** Don't bundle a bug fix with a refactor.

## Architecture orientation

`CLAUDE.md` at the repo root has a tour of the stack, the UI framework layers, and the critical gotchas (Yoga API quirks, event-bus mismatch, signed-jar issue, etc.). Read it first.

## Local development

Build runs on ModDevGradle (not ForgeGradle 6). Java 21 only. IDE run configs are generated on Gradle sync — no `genIntellijRuns` task.

```bash
./gradlew build        # compile + reobf
./gradlew test         # unit tests
./gradlew runClient    # in-game test
```

To open the dev demo screen in-game: press **`N`** in any world.
