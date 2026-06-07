package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Receives the execution result that Termux sends back via the RUN_COMMAND
 * PendingIntent, and surfaces the real reason (exit code / Termux error / stderr)
 * as a Toast so failures are no longer silent.
 *
 * Termux puts a "result" bundle on the callback intent with keys:
 *   stdout, stderr, exitCode, err (Termux-level error code), errmsg.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result: Bundle? = intent.getBundleExtra("result")
        if (result == null) {
            Toast.makeText(context, "Termux 已响应，但无结果数据（请查看 Termux 通知）", Toast.LENGTH_LONG).show()
            return
        }

        val exitCode = result.getInt("exitCode", Int.MIN_VALUE)
        val err = result.getInt("err", 0)
        val errmsg = result.getString("errmsg").orEmpty()
        val stderr = result.getString("stderr").orEmpty()

        val msg = when {
            err != 0 ->
                "Termux 拒绝/出错 (err=$err)：${errmsg.ifBlank { "无说明，检查 allow-external-apps=true" }.take(180)}"
            exitCode == 0 ->
                "✅ Pandoc 转换成功，见 下载/MarkdownReader/"
            exitCode != Int.MIN_VALUE ->
                "Pandoc 退出码=$exitCode${if (stderr.isNotBlank()) "：${stderr.take(180)}" else ""}"
            else -> {
                // Fall back to a raw dump so any unexpected key layout is still visible.
                val raw = result.keySet().joinToString("  ") { k -> "$k=${result.get(k)?.toString()?.take(80)}" }
                "Termux 结果：${raw.take(200)}"
            }
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        android.util.Log.i("TermuxResult", "err=$err exit=$exitCode errmsg=$errmsg stderr=$stderr")
    }
}
