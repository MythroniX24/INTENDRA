package com.interndra.plugin

import android.content.Context
import android.util.Log
import com.interndra.service.TermuxBridge

/**
 * GitPlugin — git operations with credential management and automation.
 *
 * Supports: status, push, pull, clone, init, commit, log, branch,
 * checkout, diff, stash, auth setup, and config management.
 *
 * Credentials are stored in Termux's git credential store (not in
 * INTERNDRA's DataStore) to keep the security surface minimal.
 */
class GitPlugin(context: Context) : IPlugin {

    companion object {
        private const val TAG = "GitPlugin"
        private const val CMD_PREFIX = "git:"
        private val TERMUX_HOME = "/data/data/com.termux/files/home"
    }

    private val bridge = TermuxBridge(context)
    private var initialized = false

    override val id: String = "git"
    override val name: String = "Git Automation"
    override val description: String = "Git operations, credential management, and GitHub integration via Termux"
    override val version: String = "2.0.0"
    override val author: String = "INTERNDRA"

    override suspend fun initialize(context: Context): Boolean {
        initialized = bridge.isTermuxInstalled()
        return true
    }

    override fun getSupportedCommands(): List<String> = listOf(
        "${CMD_PREFIX}status",
        "${CMD_PREFIX}push",
        "${CMD_PREFIX}pull",
        "${CMD_PREFIX}clone",
        "${CMD_PREFIX}init",
        "${CMD_PREFIX}commit",
        "${CMD_PREFIX}log",
        "${CMD_PREFIX}branch",
        "${CMD_PREFIX}checkout",
        "${CMD_PREFIX}diff",
        "${CMD_PREFIX}stash",
        "${CMD_PREFIX}auth_setup",
        "${CMD_PREFIX}config",
        "${CMD_PREFIX}ssh_key"
    )

    override suspend fun execute(command: String, args: Map<String, String>): PluginResult {
        if (!initialized) {
            return PluginResult(false, "", error = "Termux not installed — git requires Termux")
        }
        return try {
            when (command.removePrefix(CMD_PREFIX)) {
                "status" -> status(args)
                "push" -> push(args)
                "pull" -> pull(args)
                "clone" -> clone(args)
                "init" -> init(args)
                "commit" -> commit(args)
                "log" -> log(args)
                "branch" -> branch(args)
                "checkout" -> checkout(args)
                "diff" -> diff(args)
                "stash" -> stash(args)
                "auth_setup" -> authSetup(args)
                "config" -> config(args)
                "ssh_key" -> sshKey(args)
                else -> PluginResult(false, "", error = "Unknown git command: $command")
            }
        } catch (e: Exception) {
            PluginResult(false, "", error = "Git error: ${e.message}")
        }
    }

    private fun workdir(args: Map<String, String>): String =
        args["workdir"] ?: TERMUX_HOME

    private suspend fun status(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val result = bridge.executeShell("cd $dir && git status 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun push(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val msg = args["message"] ?: "update"
        val branch = args["branch"] ?: "HEAD"
        val cmd = buildString {
            append("cd $dir && ")
            append("git add -A 2>&1 && ")
            append("git commit --allow-empty -m '")
            append(msg.replace("'", "'\\''"))
            append("' 2>&1 && ")
            append("git push origin $branch 2>&1")
        }
        val result = bridge.executeShell(cmd, timeoutMs = 120_000L)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun pull(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val branch = args["branch"] ?: "HEAD"
        val result = bridge.executeShell("cd $dir && git pull origin $branch 2>&1", timeoutMs = 120_000L)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun clone(args: Map<String, String>): PluginResult {
        val url = args["url"] ?: return PluginResult(false, "", error = "Missing 'url' argument")
        val dir = args["dir"] ?: TERMUX_HOME
        val result = bridge.executeShell("cd $dir && git clone $url 2>&1", timeoutMs = 180_000L)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun init(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val name = args["name"] ?: "myproject"
        val cmds = buildString {
            append("mkdir -p $dir/$name && ")
            append("cd $dir/$name && ")
            append("git init 2>&1 && ")
            append("git add . 2>&1 && ")
            append("git commit -m 'initial' 2>&1")
        }
        val result = bridge.executeShell(cmds)
        return PluginResult(success = result.isSuccess,
            output = "Initialized repo '$name' at $dir/$name\n${result.stdout}",
            error = result.stderr)
    }

    private suspend fun commit(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val msg = args["message"] ?: "update"
        val cmd = buildString {
            append("cd $dir && ")
            append("git add -A 2>&1 && ")
            append("git commit -m '")
            append(msg.replace("'", "'\\''"))
            append("' 2>&1")
        }
        val result = bridge.executeShell(cmd)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun log(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val count = args["count"] ?: "20"
        val result = bridge.executeShell("cd $dir && git log --oneline -$count 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun branch(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val result = bridge.executeShell("cd $dir && git branch -a 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun checkout(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val branch = args["branch"] ?: return PluginResult(false, "", error = "Missing 'branch' argument")
        val result = bridge.executeShell("cd $dir && git checkout $branch 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun diff(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val result = bridge.executeShell("cd $dir && git diff 2>&1 | head -80")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun stash(args: Map<String, String>): PluginResult {
        val dir = workdir(args)
        val action = args["action"] ?: "push"
        val result = bridge.executeShell("cd $dir && git stash $action 2>&1")
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun authSetup(args: Map<String, String>): PluginResult {
        val username = args["username"] ?: return PluginResult(false, "", error = "Missing 'username'")
        val email = args["email"] ?: "$username@users.noreply.github.com"
        val token = args["token"] ?: ""

        val cmds = buildString {
            appendLine("git config --global user.name '$username'")
            appendLine("git config --global user.email '$email'")
            if (token.isNotBlank()) {
                // Store GitHub token in git credential store
                appendLine("echo 'https://$username:$token@github.com' > ~/.git-credentials")
                appendLine("git config --global credential.helper store")
            }
        }
        val result = bridge.executeShell(cmds.trimEnd())
        return PluginResult(
            success = result.isSuccess,
            output = if (token.isNotBlank())
                "Git configured for $username ($email) with token stored"
            else
                "Git configured for $username ($email). Use git:auth_setup with 'token' for authenticated pushes.",
            error = result.stderr
        )
    }

    private suspend fun config(args: Map<String, String>): PluginResult {
        val key = args["key"] ?: return PluginResult(false, "", error = "Missing 'key'")
        val value = args["value"]
        val dir = workdir(args)
        val cmd = if (value != null) {
            "cd $dir && git config $key '$value' 2>&1"
        } else {
            "cd $dir && git config $key 2>&1"
        }
        val result = bridge.executeShell(cmd)
        return PluginResult(success = result.isSuccess, output = result.stdout, error = result.stderr)
    }

    private suspend fun sshKey(args: Map<String, String>): PluginResult {
        val email = args["email"] ?: "interndra@local"
        val name = args["name"] ?: "id_ed25519_github"

        // Check if key exists first
        val checkResult = bridge.executeShell("ls -la ~/.ssh/${name}* 2>&1")
        if (checkResult.isSuccess) {
            val pubKey = bridge.executeShell("cat ~/.ssh/${name}.pub 2>&1")
            return PluginResult(
                success = true,
                output = buildString {
                    appendLine("SSH key already exists at ~/.ssh/$name")
                    appendLine()
                    appendLine("Public key:")
                    append(pubKey.stdout)
                    appendLine()
                    appendLine("Add this key to GitHub: https://github.com/settings/keys")
                    appendLine("Then run: ssh -T git@github.com")
                }
            )
        }

        // Generate new key
        val cmd = buildString {
            append("mkdir -p ~/.ssh && ")
            append("chmod 700 ~/.ssh && ")
            append("ssh-keygen -t ed25519 -C '$email' -f ~/.ssh/$name -N '' -q")
        }
        val result = bridge.executeShell(cmd)
        if (!result.isSuccess) {
            return PluginResult(false, output = result.stdout, error = result.stderr)
        }

        // Add to ssh-agent and output public key
        val agentResult = bridge.executeShell("eval \\$(ssh-agent) && ssh-add ~/.ssh/$name 2>&1; cat ~/.ssh/${name}.pub 2>&1")
        return PluginResult(
            success = true,
            output = buildString {
                appendLine("✅ SSH key generated at ~/.ssh/$name")
                appendLine()
                appendLine("🔑 Public key:")
                append(agentResult.stdout)
                appendLine()
                appendLine("📋 Add this key at: https://github.com/settings/keys")
                appendLine("⚡ Test: ssh -T git@github.com")
            }
        )
    }

    override fun teardown() {
        bridge.unregisterReceiver()
    }
}
