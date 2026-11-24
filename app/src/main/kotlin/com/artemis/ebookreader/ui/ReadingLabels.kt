package com.artemis.ebookreader.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.artemis.ebookreader.R
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun readingPercentage(progression: Double): String =
    NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0]).format(progression)

@Composable
internal fun lastReadLabel(timestamp: Long): String {
    val context = LocalContext.current
    val date = Date(timestamp)
    return stringResource(
        R.string.last_read,
        DateFormat.getMediumDateFormat(context).format(date),
        DateFormat.getTimeFormat(context).format(date),
    )
}
