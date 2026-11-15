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
  val scroll = ScrollView(this)
  val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  scroll.addView(list); root.addView(scroll); setContentView(root)
  Thread {
   val media = mutableListOf<Triple<android.net.Uri, Long, String>>()
   val base = MediaStore.Files.getContentUri("external")
   val columns = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATE_TAKEN, MediaStore.Files.FileColumns.DATE_MODIFIED)
   val where = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
   try {
    contentResolver.query(base, columns, where, arrayOf("1","3"), null)?.use { cursor ->
     while (cursor.moveToNext()) {
      val taken = cursor.getLong(3)
      media.add(Triple(ContentUris.withAppendedId(base, cursor.getLong(0)), if (taken > 0) taken else cursor.getLong(4)*1000, cursor.getString(2) ?: "Foto"))
     }
    }
   } catch (_: SecurityException) {}
   runOnUiThread {
    status.text = "${media.size} Medien · Neueste zuerst"
    var previous = ""
    media.sortedByDescending { it.second }.forEach { item ->
     val day = format.format(Date(item.second))
     if (day != previous) { list.addView(TextView(this).apply { text = day; textSize = 20f; setPadding(0, 18, 0, 8) }); previous = day }
     val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
     val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
     row.addView(thumb, LinearLayout.LayoutParams(100,100))
     row.addView(TextView(this).apply { text = item.third; setPadding(16,12,0,0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
     row.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(item.first, contentResolver.getType(item.first)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch (_: Exception) {} }
     list.addView(row)
     try { thumb.setImageBitmap(contentResolver.loadThumbnail(item.first, android.util.Size(160,160), null)) } catch (_: Exception) {}
    }
   }
  }.start()
 }
}