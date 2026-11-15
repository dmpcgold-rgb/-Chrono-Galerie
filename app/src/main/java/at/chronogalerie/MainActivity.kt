package at.chronogalerie

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.util.LruCache
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
 private val format = SimpleDateFormat("dd. MMMM yyyy", Locale.GERMAN)
 private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.GERMAN)
 private val executor = Executors.newFixedThreadPool(3)
 private val cache = object : LruCache<String, android.graphics.Bitmap>(24 * 1024) {
  override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount / 1024
 }
 private val favorites by lazy { getSharedPreferences("favorites", MODE_PRIVATE) }
 private var activeTab = 0
 private var showingViewer = false
 private var gridColumns = 4
 private var visiblePhotos = listOf<Triple<android.net.Uri,Long,String>>()
 private var scanGeneration = 0
 private var searchText = ""
 private var albumFilter: String? = null
 private var allMedia = listOf<Triple<android.net.Uri, Long, String>>()
 private var albums = mapOf<String, List<Triple<android.net.Uri, Long, String>>>()
 private lateinit var host: LinearLayout
 private lateinit var galleryBody: FrameLayout
 private lateinit var navigation: LinearLayout
 private lateinit var galleryStatus: TextView
 private lateinit var galleryProgress: ProgressBar
 private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
 private fun textView(t: String, size: Float = 16f) = TextView(this).apply { text=t; textSize=size; setTextColor(Color.WHITE); gravity=android.view.Gravity.CENTER_VERTICAL }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
  val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
  if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else showGallery()
 }
 override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
  super.onRequestPermissionsResult(requestCode, permissions, grantResults)
  if (requestCode == 1) showGallery()
 }
 override fun onBackPressed() {
  if (showingViewer) { showingViewer=false; setContentView(host); showTab(activeTab) }
  else if (activeTab != 0 || albumFilter != null || searchText.isNotEmpty()) { albumFilter=null; searchText=""; showTab(0) }
  else super.onBackPressed()
 }
 private fun showGallery() {
  showingViewer=false
  val generation=++scanGeneration
  window.statusBarColor = Color.BLACK
  window.navigationBarColor = Color.BLACK
  host = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); fitsSystemWindows=true }
  val header=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(8),dp(12),dp(8)) }
  header.addView(textView("Chrono Galerie",24f).apply { setTypeface(null,Typeface.BOLD) },LinearLayout.LayoutParams(0,dp(50),1f))
  header.addView(textView("⌕",30f).apply { gravity=android.view.Gravity.CENTER; setOnClickListener { search() } },LinearLayout.LayoutParams(dp(48),dp(50)))
  header.addView(textView("⋮",28f).apply { gravity=android.view.Gravity.CENTER; setOnClickListener { menu() } },LinearLayout.LayoutParams(dp(36),dp(50)))
  host.addView(header)
  galleryStatus=textView("Bilder werden geladen …",12f).apply { setPadding(dp(16),0,0,0) }
  host.addView(galleryStatus)
  galleryProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
  host.addView(galleryProgress,LinearLayout.LayoutParams(-1,dp(3)))
  galleryBody=FrameLayout(this)
  host.addView(galleryBody,LinearLayout.LayoutParams(-1,0,1f))
  navigation=LinearLayout(this).apply { setBackgroundColor(Color.rgb(44,44,46)); setPadding(0,dp(5),0,dp(14)) }
  host.addView(navigation,LinearLayout.LayoutParams(-1,dp(88)))
  setContentView(host)
  showTab(0)
  executor.execute {
   val loaded=mutableListOf<Triple<android.net.Uri,Long,String>>()
   val albumMap=mutableMapOf<String,MutableList<Triple<android.net.Uri,Long,String>>>()
   val base=MediaStore.Files.getContentUri("external")
   val columns=arrayOf(MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.MEDIA_TYPE,MediaStore.Files.FileColumns.DISPLAY_NAME,MediaStore.Files.FileColumns.DATE_TAKEN,MediaStore.Files.FileColumns.DATE_MODIFIED,MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
   try {
    contentResolver.query(base,columns,"media_type=? OR media_type=?",arrayOf("1","3"),null)?.use { cursor ->
     val total=cursor.count
     var scanned=0
     while(cursor.moveToNext()) {
      val taken=cursor.getLong(3)
      val item=Triple(ContentUris.withAppendedId(base,cursor.getLong(0)),if(taken>0)taken else cursor.getLong(4)*1000,cursor.getString(2)?:"Medium")
      loaded.add(item)
      albumMap.getOrPut(cursor.getString(5)?:"Andere"){ mutableListOf() }.add(item)
      scanned++
      if(scanned%50==0 || scanned==total) {
       val count=scanned
       val percent=if(total==0)100 else count*100/total
       runOnUiThread { galleryStatus.text="Erfasst: "+count+" / "+total+" · Noch "+(total-count)+" · "+percent+" %"; galleryProgress.progress=percent }
      }
     }
    }
   } catch(e:Exception) { runOnUiThread { if(generation==scanGeneration) galleryStatus.text="Fehler beim Einlesen: "+(e.message?:"Medienzugriff prüfen") } }
   loaded.sortByDescending { it.second }
   runOnUiThread {
    if(generation!=scanGeneration) return@runOnUiThread
    allMedia=loaded
    albums=albumMap
    galleryStatus.text=loaded.size.toString()+" Medien · Neueste zuerst"
    galleryProgress.progress=100
    galleryProgress.visibility=View.GONE
    showTab(activeTab)
   }
  }
 }
 private fun showTab(index:Int) {
  activeTab=index; galleryBody.removeAllViews(); navigation.removeAllViews()
  listOf("▧\nFotos","▣\nAlben","▤\nSammlungen","☰\nMenü").forEachIndexed { i,title ->
   navigation.addView(textView(title,13f).apply {
    gravity=android.view.Gravity.CENTER
    setTextColor(if(i==index)Color.WHITE else Color.LTGRAY)
    setBackgroundColor(if(i==index)Color.rgb(75,75,78) else Color.TRANSPARENT)
    setOnClickListener { albumFilter=null; searchText=""; showTab(i) }
   },LinearLayout.LayoutParams(0,-1,1f))
  }
  when(index) {
   0 -> showGrid(allMedia.filter { (albumFilter?.let { name -> albums[name]?.contains(it) == true } ?: true) && (searchText.isEmpty() || it.third.contains(searchText,true) || monthFormat.format(Date(it.second)).contains(searchText,true)) },true)
   1 -> {
    val scroll=ScrollView(this); val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(10),dp(20),0) }
    list.addView(textView("Alben",26f))
    albums.toList().sortedByDescending { it.second.size }.forEach { (name,items) ->
     list.addView(textView("▣   "+name+"   ("+items.size+")",19f).apply { setPadding(0,dp(22),0,dp(22)); setOnClickListener { albumFilter=name; showTab(0) } })
    }
    scroll.addView(list); galleryBody.addView(scroll)
   }
   2 -> {
    val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(15),dp(20),0) }
    list.addView(textView("Sammlungen",26f))
    val fav=allMedia.filter { favorites.getBoolean(it.first.toString(),false) }
    list.addView(textView("♥   Favoriten ("+fav.size+")",20f).apply { setPadding(0,dp(25),0,dp(25)); setOnClickListener { showGrid(fav,false) } })
    list.addView(textView("▶   Videos",20f).apply { setPadding(0,dp(25),0,dp(25)); setOnClickListener { showGrid(allMedia.filter { contentResolver.getType(it.first)?.startsWith("video/")==true },false) } })
    galleryBody.addView(list)
   }
   else -> {
    val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(15),dp(20),0) }
    list.addView(textView("Menü",26f))
    list.addView(textView("⌕   Suchen",20f).apply { setPadding(0,dp(25),0,dp(25)); setOnClickListener { search() } })
    list.addView(textView("↻   Galerie aktualisieren",20f).apply { setPadding(0,dp(25),0,dp(25)); setOnClickListener { showGallery() } })
    galleryBody.addView(list)
   }
  }
 }
 private fun showGrid(items:List<Triple<android.net.Uri,Long,String>>,timeline:Boolean) {
  visiblePhotos=items
  galleryBody.removeAllViews()
  val row=LinearLayout(this)
  val grid=GridView(this).apply {
   numColumns=gridColumns; horizontalSpacing=dp(2); verticalSpacing=dp(2)
   setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
   isVerticalScrollBarEnabled=false
   adapter=object:BaseAdapter() {
    override fun getCount()=items.size
    override fun getItem(position:Int)=items[position]
    override fun getItemId(position:Int)=position.toLong()
    override fun getView(position:Int,old:android.view.View?,parent:ViewGroup):android.view.View {
     val image=(old as? ImageView)?:ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(35,35,37)) }
     image.layoutParams=AbsListView.LayoutParams(-1,(resources.displayMetrics.widthPixels-dp(if(timeline)94 else 10))/gridColumns)
     val uri=items[position].first
     val key=uri.toString()
     image.tag=key
     val cached=cache.get(key)
     if(cached!=null) image.setImageBitmap(cached) else {
      image.setImageDrawable(null)
      executor.execute { try {
       val bitmap=contentResolver.loadThumbnail(uri,android.util.Size(240,240),null)
       cache.put(key,bitmap)
       runOnUiThread { if(image.tag==key)image.setImageBitmap(bitmap) }
      } catch(_:Exception) {} }
     }
     return image
    }
   }
   onItemClickListener=AdapterView.OnItemClickListener { _,_,position,_ -> viewer(items[position]) }
  }
  row.addView(grid,LinearLayout.LayoutParams(0,-1,1f))
  if(timeline) {
   val months=items.map { monthFormat.format(Date(it.second)) }
   val markers=months.withIndex().filter { it.index==0 || it.value!=months[it.index-1] }.map { it.value to it.index }
   val side=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=android.view.Gravity.CENTER; setBackgroundColor(Color.rgb(25,25,27)) }
   val current=textView(markers.firstOrNull()?.first?:"Jahr\nMonat",11f).apply { gravity=android.view.Gravity.CENTER }
   side.addView(current,LinearLayout.LayoutParams(dp(74),dp(78)))
   val seek=SeekBar(this).apply { rotation=270f; max=(markers.size-1).coerceAtLeast(0) }
   side.addView(seek,LinearLayout.LayoutParams(dp(74),dp(270)))
   seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(s:SeekBar?,value:Int,fromUser:Boolean) {
     if(fromUser && markers.isNotEmpty()) { val pair=markers[value.coerceIn(0,markers.lastIndex)]; current.text=pair.first; grid.setSelection(pair.second) }
    }
    override fun onStartTrackingTouch(s:SeekBar?) {}
    override fun onStopTrackingTouch(s:SeekBar?) {}
   })
   grid.setOnScrollListener(object:AbsListView.OnScrollListener {
    override fun onScrollStateChanged(view:AbsListView?,state:Int) {}
    override fun onScroll(view:AbsListView?,first:Int,visible:Int,total:Int) {
     if(markers.isEmpty() || first !in months.indices) return
     val index=markers.indexOfLast { it.second<=first }.coerceAtLeast(0)
     current.text=markers[index].first
     if(!seek.isPressed) seek.progress=index
    }
   })
   row.addView(side,LinearLayout.LayoutParams(dp(82),-1))
  }
  galleryBody.addView(row)
 }
 private fun viewer(item:Triple<android.net.Uri,Long,String>) {
  showingViewer=true
  var currentItem=item
  var currentIndex=visiblePhotos.indexOfFirst { it.first==currentItem.first }.coerceAtLeast(0)
  var startX=0f
  var startY=0f
  var requestId=0
  val screen=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); fitsSystemWindows=true }
  val heading=textView("‹    "+format.format(Date(currentItem.second)),19f).apply { setPadding(dp(18),0,0,0); setOnClickListener { showingViewer=false; setContentView(host); showTab(activeTab) } }
  screen.addView(heading,LinearLayout.LayoutParams(-1,dp(60)))
  var actionsVisible=true
  var bottomActions:LinearLayout?=null
  val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
  var refreshFavorite: (() -> Unit)?=null
  fun showPhoto(photo:Triple<android.net.Uri,Long,String>) {
   currentItem=photo
   refreshFavorite?.invoke()
   heading.text="‹    "+format.format(Date(photo.second))
   image.setImageDrawable(null)
   val id=++requestId
   executor.execute { try {
    val bitmap=contentResolver.loadThumbnail(photo.first,android.util.Size(2000,2000),null)
    runOnUiThread { if(showingViewer && id==requestId) image.setImageBitmap(bitmap) }
   } catch(_:Exception) {} }
  }
  image.setOnTouchListener { _,event ->
   when(event.actionMasked) {
    MotionEvent.ACTION_DOWN -> { startX=event.x; startY=event.y; true }
    MotionEvent.ACTION_UP -> {
     val dx=event.x-startX
     val dy=event.y-startY
     if(kotlin.math.abs(dx)>dp(65) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.3f) {
      val next=currentIndex+if(dx<0)1 else -1
      if(next in visiblePhotos.indices) { currentIndex=next; showPhoto(visiblePhotos[next]) }
     } else if(kotlin.math.abs(dx)<dp(20) && kotlin.math.abs(dy)<dp(20)) {
      actionsVisible=!actionsVisible
      bottomActions?.visibility=if(actionsVisible)View.VISIBLE else View.GONE
      heading.visibility=if(actionsVisible)View.VISIBLE else View.INVISIBLE
     }
     true
    }
    else -> true
   }
  }
  screen.addView(image,LinearLayout.LayoutParams(-1,0,1f))
  showPhoto(item)
  val actions=LinearLayout(this)
  bottomActions=actions
  val fav=textView(if(favorites.getBoolean(currentItem.first.toString(),false))"♥ Favorit" else "♡ Favorit").apply { gravity=android.view.Gravity.CENTER }
  refreshFavorite={ fav.text=if(favorites.getBoolean(currentItem.first.toString(),false))"♥ Favorit" else "♡ Favorit" }
  fav.setOnClickListener { val value=!favorites.getBoolean(currentItem.first.toString(),false); favorites.edit().putBoolean(currentItem.first.toString(),value).apply(); fav.text=if(value)"♥ Favorit" else "♡ Favorit" }
  actions.addView(fav,LinearLayout.LayoutParams(0,dp(60),1f))
  actions.addView(textView("↗ Teilen").apply { gravity=android.view.Gravity.CENTER; setOnClickListener {
   startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type=contentResolver.getType(currentItem.first)?:"image/*"; putExtra(Intent.EXTRA_STREAM,currentItem.first); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"Teilen"))
  } },LinearLayout.LayoutParams(0,dp(60),1f))
  actions.addView(textView("⤢ Öffnen").apply { gravity=android.view.Gravity.CENTER; setOnClickListener {
   try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(currentItem.first,contentResolver.getType(currentItem.first)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } catch(_:Exception) {}
  } },LinearLayout.LayoutParams(0,dp(60),1f))
  actions.addView(textView("ⓘ Info").apply { gravity=android.view.Gravity.CENTER; setOnClickListener {
   android.app.AlertDialog.Builder(this@MainActivity).setTitle(currentItem.third).setMessage("Aufnahme: "+format.format(Date(currentItem.second))+"\nDatei: "+currentItem.third+"\nTyp: "+(contentResolver.getType(currentItem.first)?:"Unbekannt")).setPositiveButton("OK",null).show()
  } },LinearLayout.LayoutParams(0,dp(60),1f))
  screen.addView(actions); setContentView(screen)
 }
 private fun search() {
  val input=EditText(this).apply { hint="Dateiname oder Monat"; setSingleLine(true) }
  android.app.AlertDialog.Builder(this).setTitle("Fotos suchen").setView(input).setPositiveButton("Suchen") { _,_ -> searchText=input.text.toString(); albumFilter=null; showTab(0) }.setNegativeButton("Abbrechen",null).show()
 }
 private fun menu() {
  android.app.AlertDialog.Builder(this).setItems(arrayOf("Suchen","Aktualisieren","Alle Fotos")) { _,i ->
   when(i) { 0->search(); 1->showGallery(); else->{ albumFilter=null; searchText=""; showTab(0) } }
  }.show()
 }
}
