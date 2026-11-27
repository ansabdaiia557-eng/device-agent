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
        t1.join(timeoutSec * 1000); t2.join(2000)
        if (t1.isAlive()) { try { p.destroy() } catch (e: Exception) {}; return "timeout" }
        var code: Int? = null
        val w = Thread { try { code = p.waitFor() } catch (e: Exception) {} }
        w.start(); w.join(2500)
        if (code == null) code = try { p.exitValue() } catch (e: Throwable) { null }
        try { p.destroy() } catch (e: Exception) {}
        val text = out.toString().trim()
        val c = code?.let { "exit=$it" } ?: "exit=0 (finished)"
        return c + (if (text.isNotEmpty()) "\n" + text.take(maxChars) else "")
    }

    /** Reads a file as the shell user (binary safe). */
    fun readBytes(path: String, maxBytes: Int = 15_000_000): ByteArray {
        val p = spawn(listOf("cat", path))
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(65536)
        val ins = p.inputStream
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) { try { p.destroy() } catch (e: Exception) {}; throw RuntimeException("file too large") }
        }
        try { p.destroy() } catch (e: Exception) {}
        return out.toByteArray()
    }
}
