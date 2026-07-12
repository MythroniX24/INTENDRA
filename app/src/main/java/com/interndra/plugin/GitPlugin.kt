package com.interndra.plugin

import android.content.Context
import com.interndra.service.ShellExecutor

class GitPlugin(context: Context) : IPlugin {
    companion object { private const val CMD_PREFIX = "git:" }
    override val id = "git"; override val name = "Git Automation"
    override val description = "Git operations via built-in shell"
    override val version = "2.1.0"; override val author = "INTERNDRA"

    override suspend fun initialize(context: Context) = true
    override fun getSupportedCommands() = listOf("${CMD_PREFIX}status","${CMD_PREFIX}push","${CMD_PREFIX}pull","${CMD_PREFIX}clone","${CMD_PREFIX}init","${CMD_PREFIX}commit","${CMD_PREFIX}log","${CMD_PREFIX}branch","${CMD_PREFIX}checkout","${CMD_PREFIX}diff","${CMD_PREFIX}stash","${CMD_PREFIX}auth_setup","${CMD_PREFIX}config","${CMD_PREFIX}ssh_key")

    override suspend fun execute(command: String, args: Map<String, String>) = try {
        when (command.removePrefix(CMD_PREFIX)) {
            "status" -> status(args); "push" -> push(args); "pull" -> pull(args)
            "clone" -> clone(args); "init" -> init(args); "commit" -> commit(args)
            "log" -> log(args); "branch" -> branch(args); "checkout" -> checkout(args)
            "diff" -> diff(args); "stash" -> stash(args); "auth_setup" -> authSetup(args)
            "config" -> config(args); "ssh_key" -> sshKey(args)
            else -> PluginResult(false, "", error = "Unknown: $command")
        }
    } catch (e: Exception) { PluginResult(false, "", error = "Git error: ${e.message}") }

    private fun result(r: com.interndra.service.ShellExecutionResult) = PluginResult(r.isSuccess, r.stdout, error = r.stderr)
    private suspend fun shell(cmd: String, timeoutMs: Long = 60_000L) = result(ShellExecutor.runAsync(cmd, timeoutMs))
    private fun dir(args: Map<String, String>) = args["workdir"] ?: "/sdcard"

    private suspend fun status(args: Map<String, String>) = shell("cd ${dir(args)} && git status 2>&1")
    private suspend fun push(args: Map<String, String>): PluginResult {
        val d = dir(args); val msg = args["message"] ?: "update"; val branch = args["branch"] ?: "HEAD"
        return shell("cd $d && git add -A 2>&1 && git commit --allow-empty -m '$msg' 2>&1 && git push origin $branch 2>&1", 120_000L)
    }
    private suspend fun pull(args: Map<String, String>) = shell("cd ${dir(args)} && git pull origin ${args["branch"] ?: "HEAD"} 2>&1", 120_000L)
    private suspend fun clone(args: Map<String, String>): PluginResult {
        val url = args["url"] ?: return PluginResult(false, "", error = "Missing 'url'")
        return shell("cd ${args["dir"] ?: "/sdcard"} && git clone $url 2>&1", 180_000L)
    }
    private suspend fun init(args: Map<String, String>): PluginResult {
        val d = dir(args); val name = args["name"] ?: "myproject"
        return shell("mkdir -p $d/$name && cd $d/$name && git init 2>&1 && git add . 2>&1 && git commit -m 'initial' 2>&1")
    }
    private suspend fun commit(args: Map<String, String>): PluginResult {
        val d = dir(args); val msg = args["message"] ?: "update"
        return shell("cd $d && git add -A 2>&1 && git commit -m '$msg' 2>&1")
    }
    private suspend fun log(args: Map<String, String>) = shell("cd ${dir(args)} && git log --oneline -${args["count"] ?: "20"} 2>&1")
    private suspend fun branch(args: Map<String, String>) = shell("cd ${dir(args)} && git branch -a 2>&1")
    private suspend fun checkout(args: Map<String, String>): PluginResult {
        val branch = args["branch"] ?: return PluginResult(false, "", error = "Missing 'branch'")
        return shell("cd ${dir(args)} && git checkout $branch 2>&1")
    }
    private suspend fun diff(args: Map<String, String>) = shell("cd ${dir(args)} && git diff 2>&1 | head -80")
    private suspend fun stash(args: Map<String, String>) = shell("cd ${dir(args)} && git stash ${args["action"] ?: "push"} 2>&1")
    private suspend fun authSetup(args: Map<String, String>): PluginResult {
        val username = args["username"] ?: return PluginResult(false, "", error = "Missing 'username'")
        val email = args["email"] ?: "$username@users.noreply.github.com"
        val r = shell("git config --global user.name '$username' && git config --global user.email '$email'")
        return PluginResult(r.success, if (r.success) "Git configured for $username ($email)" else r.output, error = r.error)
    }
    private suspend fun config(args: Map<String, String>): PluginResult {
        val key = args["key"] ?: return PluginResult(false, "", error = "Missing 'key'")
        val value = args["value"]
        return if (value != null) shell("cd ${dir(args)} && git config $key '$value' 2>&1")
        else shell("cd ${dir(args)} && git config $key 2>&1")
    }
    private suspend fun sshKey(args: Map<String, String>): PluginResult {
        val email = args["email"] ?: "interndra@local"; val name = args["name"] ?: "id_ed25519"
        val check = shell("ls ~/.ssh/${name}* 2>&1")
        if (check.success) {
            val pub = shell("cat ~/.ssh/${name}.pub 2>&1")
            return PluginResult(true, "SSH key exists at ~/.ssh/$name\n\nPublic key:\n${pub.output}\n\nAdd at: https://github.com/settings/keys")
        }
        val r = shell("mkdir -p ~/.ssh && chmod 700 ~/.ssh && ssh-keygen -t ed25519 -C '$email' -f ~/.ssh/$name -N '' -q 2>&1")
        if (!r.success) return PluginResult(false, r.output, error = r.error)
        val pub = shell("cat ~/.ssh/${name}.pub 2>&1")
        return PluginResult(true, "✅ SSH key generated at ~/.ssh/$name\n\n🔑 Public key:\n${pub.output}\n\nAdd at: https://github.com/settings/keys")
    }
    override fun teardown() {}
}
