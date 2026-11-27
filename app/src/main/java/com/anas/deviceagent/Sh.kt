package com.anas.deviceagent

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** Runs commands as the shell user through Shizuku. */
object Sh {
    fun binderReady(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = try {
        binderReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun request(code: Int) { try { Shizuku.requestPermission(code) } catch (e: Throwable) {} }

    fun spawn(cmd: List<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, cmd.toTypedArray(), null, null) as Process
    }

    /** Returns "exit=N" plus the output (stdout and stderr), cut to maxChars. */
    fun exec(cmd: List<String>, timeoutSec: Long = 15, maxChars: Int = 700): String {
        val p = spawn(cmd)
        val out = StringBuilder()
        val t1 = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (e: Exception) {} }
        val t2 = Thread { try { out.append(p.errorStream.bufferedReader().readText()) } catch (e: Exception) {} }
        t1.start(); t2.start()
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) { try { p.destroy() } catch (e: Exception) {} ; return "timeout" }
        t1.join(1500); t2.join(1500)
        val text = out.toString().trim()
        return "exit=${p.exitValue()}" + (if (text.isNotEmpty()) "\n" + text.take(maxChars) else "")
    }
}
