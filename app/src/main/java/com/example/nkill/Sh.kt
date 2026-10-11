package com.example.nkill

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** All Shizuku-powered (shell-level) actions. */
object Sh {
    enum class State { NOT_RUNNING, NO_PERMISSION, READY }

    fun state(): State = when {
        !Shizuku.pingBinder() -> State.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
        else -> State.READY
    }

    fun ready() = state() == State.READY

    /** Shizuku.newProcess is private since 13.x; reflection is the common lightweight workaround. */
    private val newProc by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    fun exec(vararg cmd: String): String = try {
        val p = newProc.invoke(null, arrayOf(*cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (e: Throwable) {
        "error: ${e.message}"
    }

    // App-level block: the notification is never posted (no flicker, persistent ones included).
    fun blockApp(pkg: String) = exec("appops", "set", pkg, "POST_NOTIFICATION", "ignore")
    fun unblockApp(pkg: String) = exec("appops", "set", pkg, "POST_NOTIFICATION", "default")

    /** Re-apply every app-level rule. Idempotent; call on Shizuku (re)connect and app open. */
    fun syncBlocks(ctx: Context) {
        if (!ready()) return
        val pkgs = RuleStore.load(ctx).filter { it.text.isEmpty() }.map { it.pkg }.toSet()
        thread { pkgs.forEach { blockApp(it) } }
    }

    fun allowListener(ctx: Context): String =
        exec("cmd", "notification", "allow_listener", "${ctx.packageName}/${BlockerService::class.java.name}")
}
