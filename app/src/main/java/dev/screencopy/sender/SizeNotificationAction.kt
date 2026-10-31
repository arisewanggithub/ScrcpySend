package dev.screencopy.sender

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object SizeNotificationAction {
    const val ACTION = "dev.screencopy.sender.SIZE_OVERRIDE"
    fun intent(context: Context, token: Long, version: Long, desired: Boolean): Intent =
        Intent(context, CastService::class.java).setAction(ACTION)
            .setData(uri(token, version, desired))
            .putExtra("request", token).putExtra("version", version).putExtra("fullScreen", desired)

    fun valid(intent: Intent, token: Long, version: Long, desired: Boolean): Boolean =
        intent.action == ACTION && token > 0 && version >= 0 &&
            intent.hasExtra("fullScreen") && intent.getLongExtra("request", -1) == token &&
            intent.getLongExtra("version", -1) == version && intent.getBooleanExtra("fullScreen", !desired) == desired &&
            intent.data == uri(token, version, desired)

    private fun uri(token: Long, version: Long, desired: Boolean): Uri =
        Uri.parse("screencopy://size/$token/$version/${if (desired) 1 else 0}/request")
}
