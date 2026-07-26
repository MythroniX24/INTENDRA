# Security Policy

## Supported Versions

| Version | Supported          |
|---------|-------------------|
| 2.x.x   | ✅ Active development |
| < 2.0   | ❌ Not supported   |

## Reporting a Vulnerability

INTENDRA takes security seriously. The app runs AI-powered shell commands on your device, making security a top priority.

### How to Report

**Please do NOT report security vulnerabilities via public GitHub issues.**

Instead, report them directly to the maintainers:

1. **Open a draft security advisory** on GitHub:
   - Go to [https://github.com/MythroniX24/INTENDRA/security/advisories](https://github.com/MythroniX24/INTENDRA/security/advisories)
   - Click "New draft security advisory"
   - Provide detailed information about the vulnerability

2. **Email** (if GitHub advisories are unavailable):
   - Contact the repository owner directly via their GitHub profile

### What to Include

- Type of vulnerability (e.g., command injection, privilege escalation)
- Full description of the issue
- Steps to reproduce
- Affected components (Safety Engine, Terminal, Shizuku, etc.)
- Suggested fix (if available)
- Your contact information (optional)

### Response Timeline

| Time | Action |
|------|--------|
| 24-48 hours | Acknowledgment of receipt |
| 1 week | Initial assessment and severity classification |
| 2-4 weeks | Patch development (depending on severity) |
| Upon release | Public disclosure + credit in release notes |

## Built-in Security Features

INTENDRA includes several security mechanisms designed to protect users:

### Safety Engine
- **Command Validation** — 40+ regex patterns block destructive commands (`rm -rf`, `chmod 777`, etc.)
- **Command Normalization** — Strips bypass attempts (base64, IFS substitution, quote obfuscation)
- **Batch Processing** — Short-circuit evaluation for multi-command validation
- **Structured Verdicts** — Clear safety status and reasoning for every command

### Privacy Protections
- **Privacy Modes** — Local-only, Cloud-only, or Hybrid mode selection
- **Emergency Privacy Lock** — Instant disconnection from all cloud/external services
- **No Telemetry** — The app does not collect usage data
- **Explicit Cloud Consent** — Per-session opt-in for cloud AI features

### Terminal Security
- **PTY Sandboxing** — Terminal sessions run in an embedded environment
- **Shizuku Authorization** — Elevated commands require explicit user authorization
- **Command Gating** — Destructive operations require user confirmation
- **Logging** — All executed commands are logged for audit

## Security Best Practices for Users

1. **Review commands before execution** — Always verify what commands the AI is about to run
2. **Use Local mode when offline** — All processing stays on-device
3. **Authorize Shizuku selectively** — Only grant Shizuku access when you need elevated commands
4. **Keep the app updated** — Always use the latest version for security patches
5. **Use the Emergency Lock** — Activate it when lending your device to someone else

## Vulnerability Disclosure

We believe in responsible disclosure. If you discover a vulnerability:

1. Report it privately (see above)
2. Allow us reasonable time to fix it
3. Do not disclose it publicly until a fix is released

We will credit you in the release notes for verified security contributions.

## Security-Related Configuration

See [README.md](README.md) for detailed configuration of security features including:
- Privacy mode switching
- Emergency Privacy Lock activation
- Shizuku authorization
- Safety Engine customization

---

*Last updated: 2025*
