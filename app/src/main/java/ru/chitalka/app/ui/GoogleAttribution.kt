package ru.chitalka.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ru.chitalka.app.R

/** Official, unmodified attribution badge distributed by Google. */
@Composable fun GoogleAttribution() {
    val context = LocalContext.current
    Box(Modifier.heightIn(min = 48.dp).clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/"))) }, contentAlignment = Alignment.CenterStart) {
        Image(painterResource(if (LocalAppTheme.current == AppTheme.NIGHT) R.drawable.google_translate_white else R.drawable.google_translate_color),
            "Powered by Google Translate", Modifier.size(176.dp, 16.dp))
    }
}
