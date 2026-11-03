package com.mrk.filerag

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var db: Db

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App(db) }
            }
        }
    }
}

fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

fun openExternal(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Cannot open: " + e.message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun App(db: Db) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var access by remember { mutableStateOf(hasAccess(ctx)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) access = hasAccess(ctx) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = hasAccess(ctx)
    }

    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<Hit>()) }
    var viewing by remember { mutableStateOf<Hit?>(null) }
    var indexing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var total by remember { mutableStateOf(db.count()) }
    var job by remember { mutableStateOf<Job?>(null) }

    val view = LocalView.current
    SideEffect { view.keepScreenOn = indexing }

    LaunchedEffect(query, total) {
        delay(250)
        hits = withContext(Dispatchers.IO) { db.search(query) }
    }

    BackHandler(enabled = viewing != null) { viewing = null }

    fun startIndex() {
        indexing = true
        job = scope.launch(Dispatchers.Default) {
            try {
                Indexer(ctx.applicationContext, db).run(
                    Environment.getExternalStorageDirectory(),
                    { n, name -> progress = "$n new: $name" },
                    { isActive }
                )
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    indexing = false
                    progress = ""
                    total = db.count()
                }
            }
        }
    }

    if (!access) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("File RAG", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(8.dp))
            Text("Allow access to all files so the app can index your documents and photos. Everything stays on your phone.")
            Spacer(Modifier.padding(8.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    )
                } else {
                    legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) { Text("Grant access") }
        }
        return
    }

    val v = viewing
    if (v != null) {
        Viewer(v) { viewing = null }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("File RAG", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Describe a file or photo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (indexing) job?.cancel() else startIndex() }) {
                Text(if (indexing) "Stop" else "Index files")
            }
            Spacer(Modifier.width(12.dp))
            Text("$total files indexed", fontSize = 13.sp)
        }
        if (progress.isNotEmpty()) {
            Text(progress, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hits.isEmpty()) {
            Text(
                if (total == 0) "Tap Index files first. Keep the app open until it finishes."
                else if (query.isBlank()) "Type what you are looking for."
                else "No matches.",
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(hits) { h -> ResultRow(h) { viewing = h } }
        }
    }
}

@Composable
fun ResultRow(h: Hit, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(h.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (h.snippet.isNotBlank()) Text(h.snippet, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            h.path, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun Viewer(hit: Hit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val file = File(hit.path)
    val ext = file.extension.lowercase()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { openExternal(ctx, file) }) { Text("Open with...") }
        }
        SelectionContainer {
            Text("Path: " + hit.path, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ext in IMG_EXT -> ImageView(file)
                ext == "pdf" -> PdfView(file)
                ext in TEXT_EXT -> TextView(file)
                else -> Text("No built-in viewer for this file type. Tap Open with...")
            }
        }
    }
}

@Composable
fun ImageView(file: File) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeScaled(file.path, 2048) }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else {
        CircularProgressIndicator()
    }
}

@Composable
fun TextView(file: File) {
    val text by produceState("Loading...", file) {
        value = withContext(Dispatchers.IO) { readHead(file, 200_000) }
    }
    SelectionContainer {
        Text(text, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

@Composable
fun PdfView(file: File) {
    val holder = remember(file) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            Pair(PdfRenderer(pfd), pfd)
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(holder) {
        onDispose {
            try { holder?.first?.close() } catch (e: Exception) {}
            try { holder?.second?.close() } catch (e: Exception) {}
        }
    }
    val mutex = remember { Mutex() }
    if (holder == null) {
        Text("Cannot open this PDF here. Tap Open with...")
        return
    }
    val renderer = holder.first
    LazyColumn(Modifier.fillMaxSize()) {
        items(renderer.pageCount) { i ->
            val bmp by produceState<Bitmap?>(null, i) {
                value = withContext(Dispatchers.IO) {
                    mutex.withLock {
                        try { renderPage(renderer, i, 1000) } catch (e: Exception) { null }
                    }
                }
            }
            val b = bmp
            if (b != null) {
                Image(
                    b.asImageBitmap(), null,
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
