# Contributing to INTENDRA

First off, thank you for considering contributing to INTENDRA! We welcome contributions from everyone, whether you're fixing a bug, adding a feature, or improving documentation.

## Code of Conduct

This project and everyone participating in it is governed by our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Bugs

1. **Check existing issues** — Search the [issue tracker](https://github.com/MythroniX24/INTENDRA/issues) to see if the bug has already been reported.
2. **Create a new issue** — Use the bug report template and include:
   - Device model and Android version
   - INTENDRA version (from Settings → About)
   - Steps to reproduce
   - Expected vs actual behavior
   - Logcat output (if applicable)

### Suggesting Features

1. Open a [feature request](https://github.com/MythroniX24/INTENDRA/issues/new) with:
   - Clear description of the feature
   - Why it's useful for INTENDRA
   - Any implementation ideas you have

### Pull Request Process

1. **Fork** the repository and create your branch from `main`:
   ```bash
   git checkout -b feat/your-feature-name
   ```

2. **Set up your environment**:
   ```bash
   # Android Studio Hedgehog+ recommended
   # Install NDK 27+ via SDK Manager
   ```

3. **Follow code style**:
   - Kotlin: Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
   - Use `kotlin.code.style=official` (set in `gradle.properties`)
   - Consider adding a linter (e.g., ktlint, detekt) to your local development setup

4. **Write tests** — All new features should include unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
   - Tests go in `app/src/test/java/com/interndra/`
   - Use Mockk for mocking, Truth for assertions

5. **Ensure native code compiles** (if modifying JNI):
   ```bash
   ./gradlew :app:compileDebugKotlin
   ```
   The full NDK build (`assembleDebug`) runs in CI.

6. **Commit with conventional commits**:
   ```
   feat: new feature description
   fix: bug fix description
   refactor: code restructuring
   docs: documentation changes
   test: test additions/fixes
   chore: build/config changes
   perf: performance improvements
   ```

7. **Push and open a Pull Request**:
   ```bash
   git push origin feat/your-feature-name
   ```
   Then open a PR on GitHub. CI will automatically run:
   - Kotlin compilation
   - Native APK build (NDK + CMake)
   - All 672+ unit tests

8. **Address review feedback** — A maintainer will review your PR. Please respond to comments and make requested changes.

## Development Guidelines

### Architecture
- **MVVM + Repository Pattern** — ViewModels manage UI state, repositories handle data
- **StateFlow** — Use StateFlow for reactive state management
- **Dependency Injection** — Manual DI via constructor injection (no Hilt/Dagger)

### Native Code (JNI)
- C source in `app/src/main/jni/`
- CMakeLists.txt at `app/src/main/jni/CMakeLists.txt`
- Kotlin JNI wrapper in `com.interndra.jni.JniTermux`
- Graceful fallback required if native lib fails to load

### Testing Requirements
| Area | Required |
|------|----------|
| Bug fixes | Add test that reproduces the bug |
| New features | ≥ 80% coverage on new code |
| AI Engine changes | Unit tests for all new logic paths |
| Terminal changes | Emulator + session tests |
| Safety Engine changes | Tests for new validation patterns |

### CI Pipeline
The GitHub Actions workflow (`build.yml`) runs three steps sequentially:
1. `:app:compileDebugKotlin` — Kotlin compilation check
2. `assembleDebug` — Full APK build with native code
3. `testDebugUnitTest` — All unit tests

All three must pass for a PR to be merged.

## Getting Help

- Open an issue for bugs/feature requests
- Join discussions in the issue tracker
- Check the [README](README.md) for project overview
- See [PAPER.md](PAPER.md) for detailed technical documentation

## Project Structure

```
app/src/main/java/com/interndra/
├── ai/          — AI engines, orchestrator, safety
├── agent/       — Agent implementations
├── data/        — Database, DAOs, repositories
├── jni/         — JNI wrapper for native code
├── plugin/      — Git, Termux, package plugins
├── search/      — Web search pipeline
├── service/     — Shizuku, TermuxEnvironment, shell
├── services/    — Accessibility, notification services
├── terminal/    — PTY, ANSI emulator, byte queue
├── ui/          — Compose UI, screens, viewmodels
└── util/        — Utility classes
```

Thank you for contributing! 🚀
