package at.chronogalerie

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
 private val format = SimpleDateFormat("dd. MMMM yyyy", Locale.GERMAN)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
  if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else showGallery()
 }
 override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  showGallery()
 }
 private fun showGallery() {
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 0) }
  val heading = TextView(this).apply { text = "Chrono Galerie"; textSize = 28f }
  root.addView(heading)
  val status = TextView(this).apply { text = "Lade Medien …" }
  root.addView(status)
  val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
  root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
  val scroll = ScrollView(this)
  val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  val galleryArea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  galleryArea.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
  val timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
  val timelineLabel = TextView(this).apply { text = "Jahr\nMonat"; textSize = 12f; gravity = android.view.Gravity.CENTER }
  timeline.addView(timelineLabel)
  val yearSeek = SeekBar(this).apply { rotation = 270f }
  timeline.addView(yearSeek, LinearLayout.LayoutParams(76, 260))
  galleryArea.addView(timeline, LinearLayout.LayoutParams(94, ViewGroup.LayoutParams.MATCH_PARENT))
  root.addView(galleryArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
  setContentView(root)
  Thread {
   val media = mutableListOf<Triple<android.net.Uri, Long, String>>()
   val base = MediaStore.Files.getContentUri("external")
   val columns = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATE_TAKEN, MediaStore.Files.FileColumns.DATE_MODIFIED)
   val where = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
   try {
    contentResolver.query(base, columns, where, arrayOf("1","3"), null)?.use { cursor ->
     val total = cursor.count
     var scanned = 0
     runOnUiThread { status.text = "Erfasse Medien: 0 / $total · Noch $total · 0 %"; progress.progress = 0 }
     while (cursor.moveToNext()) {
      val taken = cursor.getLong(3)
      media.add(Triple(ContentUris.withAppendedId(base, cursor.getLong(0)), if (taken > 0) taken else cursor.getLong(4)*1000, cursor.getString(2) ?: "Foto"))
      scanned++
      if (scanned % 50 == 0 || scanned == total) {
       val done = scanned
       val percent = if (total == 0) 100 else done * 100 / total
       runOnUiThread { status.text = "Erfasse Medien: $done / $total · Noch ${total - done} · $percent %"; progress.progress = percent }
      }
     }
    }
   } catch (_: SecurityException) {}
   runOnUiThread {
    status.text = "Erfasst: ${media.size} / ${media.size} · Noch 0 · 100 %"
    progress.progress = 100
    val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.GERMAN)
    val monthTargets = mutableListOf<Pair<String, Int>>()
    var previousMonth = ""
    var previous = ""
    media.sortedByDescending { it.second }.forEach { item ->
     val month = monthFormat.format(Date(item.second))
     if (month != previousMonth) {
      val marker = TextView(this).apply { text = month; textSize = 24f; setPadding(0, 24, 0, 10) }
      list.addView(marker)
      monthTargets.add(month to list.childCount - 1)
      previousMonth = month
     }
     val day = format.format(Date(item.second))
     if (day != previous) { list.addView(TextView(this).apply { text = day; textSize = 20f; setPadding(0, 18, 0, 8) }); previous = day }
     val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
     val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
     row.addView(thumb, LinearLayout.LayoutParams(100,100))
     row.addView(TextView(this).apply { text = item.third; setPadding(16,12,0,0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
     row.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(item.first, contentResolver.getType(item.first)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch (_: Exception) {} }
     list.addView(row)
     // Thumbnails are loaded separately to avoid blocking the main thread.
     Thread {
      try {
       val bitmap = contentResolver.loadThumbnail(item.first, android.util.Size(160,160), null)
       runOnUiThread { thumb.setImageBitmap(bitmap) }
      } catch (_: Exception) {}
     }.start()
    }
    yearSeek.max = (monthTargets.size - 1).coerceAtLeast(0)
    yearSeek.progress = 0
    if (monthTargets.isNotEmpty()) timelineLabel.text = monthTargets.first().first
    yearSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
     override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
      if (!fromUser || monthTargets.isEmpty()) return
      val index = value.coerceIn(0, monthTargets.lastIndex)
      val (label, childIndex) = monthTargets[index]
      timelineLabel.text = label
      val target = list.getChildAt(childIndex)
      scroll.post { scroll.smoothScrollTo(0, target.top) }
     }
     override fun onStartTrackingTouch(seekBar: SeekBar?) {}
     override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
   }
  }.start()
 }
}