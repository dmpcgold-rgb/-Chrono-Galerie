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
 private var savedGridPosition = 0
 private var viewerGeneration = 0
 private var lastGalleryScroll = 0
 private var visiblePhotos = listOf<Triple<android.net.Uri,Long,String>>()
 private var scanGeneration = 0
 private var searchText = ""
 private var albumFilter: String? = null
 private var allMedia = listOf<Triple<android.net.Uri, Long, String>>()
 private var videoUris = setOf<android.net.Uri>()
 private var albums = mapOf<String, List<Triple<android.net.Uri, Long, String>>>()
 private lateinit var host: LinearLayout
 private lateinit var galleryBody: FrameLayout
 private lateinit var navigation: LinearLayout
 private lateinit var galleryStatus: TextView
 private lateinit var galleryProgress: ProgressBar
 private val accent=Color.rgb(167,91,255)
 private fun rounded(color:Int,radius:Int):android.graphics.drawable.GradientDrawable =
  android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
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
  if (showingViewer) { showingViewer=false; viewerGeneration++; setContentView(host); showTab(activeTab) }
  else if (activeTab != 0 || albumFilter != null || searchText.isNotEmpty()) { albumFilter=null; searchText=""; showTab(0) }
  else super.onBackPressed()
 }
 private fun showGallery() {
  showingViewer=false
  val generation=++scanGeneration
  window.statusBarColor = Color.BLACK
  window.navigationBarColor = Color.BLACK
  host = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(11,11,20)); fitsSystemWindows=true }
  val header=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(8),dp(12),dp(8)) }
  header.addView(ImageView(this).apply { setImageResource(at.chronogalerie.R.drawable.chrono_icon); scaleType=ImageView.ScaleType.FIT_CENTER },LinearLayout.LayoutParams(dp(42),dp(42)))
  header.addView(textView("Chrono Galerie",23f).apply { setTypeface(null,Typeface.BOLD) },LinearLayout.LayoutParams(0,dp(50),1f))
  header.addView(textView("⌕",30f).apply { gravity=android.view.Gravity.CENTER; setOnClickListener { search() } },LinearLayout.LayoutParams(dp(48),dp(50)))
  header.addView(textView("⋮",28f).apply { gravity=android.view.Gravity.CENTER; setOnClickListener { menu() } },LinearLayout.LayoutParams(dp(36),dp(50)))
  host.addView(header)
  val categories=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(5),dp(12),dp(9)) }
  listOf("Fotos","Alben","Videos").forEachIndexed { i,label ->
   val chip=textView(label,14f).apply {
    gravity=android.view.Gravity.CENTER
    background=rounded(if(i==0) Color.rgb(96,50,154) else Color.rgb(22,21,35),20)
    setOnClickListener {
     when(i) {
      0 -> { albumFilter=null; searchText=""; showTab(0) }
      1 -> showTab(1)
      else -> { showTab(2); showGrid(allMedia.filter { videoUris.contains(it.first) },false) }
     }
    }
   }
   val params=LinearLayout.LayoutParams(0,dp(37),1f).apply { setMargins(dp(3),0,dp(3),0) }
   categories.addView(chip,params)
  }
  host.addView(categories)
  galleryStatus=textView("Bilder werden geladen …",12f).apply { setTextColor(Color.rgb(195,181,216)); setPadding(dp(16),0,0,0) }
  host.addView(galleryStatus)
  galleryProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
  galleryProgress.progressTintList=android.content.res.ColorStateList.valueOf(accent)
  host.addView(galleryProgress,LinearLayout.LayoutParams(-1,dp(3)))
  galleryBody=FrameLayout(this)
  host.addView(galleryBody,LinearLayout.LayoutParams(-1,0,1f))
  navigation=LinearLayout(this).apply { background=rounded(Color.rgb(24,22,38),22); setPadding(dp(5),dp(6),dp(5),dp(14)) }
  host.addView(navigation,LinearLayout.LayoutParams(-1,dp(88)))
  setContentView(host)
  showTab(0)
  executor.execute {
   val loaded=mutableListOf<Triple<android.net.Uri,Long,String>>()
   val albumMap=mutableMapOf<String,MutableList<Triple<android.net.Uri,Long,String>>>()
   val scannedVideos=mutableSetOf<android.net.Uri>()
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
      if(cursor.getInt(1)==MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) scannedVideos.add(item.first)
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
    videoUris=scannedVideos
    albums=albumMap
    galleryStatus.text="Alle Medien    "+String.format(Locale.GERMAN,"%,d",loaded.size)+"   · Neueste zuerst"
    galleryProgress.progress=100
    galleryProgress.visibility=View.GONE
    showTab(activeTab)
   }
  }
 }
 private fun showTab(index:Int) {
  activeTab=index; galleryBody.removeAllViews(); navigation.removeAllViews()
  val navIcons=listOf("▧","▱","▤","☰")
  val navNames=listOf("Galerie","Alben","Sammlungen","Mehr")
  navNames.forEachIndexed { i,name ->
   val selected=i==index
   val tab=LinearLayout(this).apply {
    orientation=LinearLayout.VERTICAL
    gravity=android.view.Gravity.CENTER
    background=if(selected) rounded(Color.rgb(87,45,139),18) else rounded(Color.TRANSPARENT,18)
    setOnClickListener { albumFilter=null; searchText=""; showTab(i) }
   }
   tab.addView(textView(navIcons[i],23f).apply { gravity=android.view.Gravity.CENTER; setTextColor(if(selected) Color.WHITE else Color.rgb(197,189,214)) })
   tab.addView(textView(name,11f).apply { gravity=android.view.Gravity.CENTER; setTextColor(if(selected) Color.WHITE else Color.rgb(197,189,214)) })
   navigation.addView(tab,LinearLayout.LayoutParams(0,-1,1f))
  }
  when(index) {
   0 -> showGrid(allMedia.filter { (albumFilter?.let { name -> albums[name]?.contains(it) == true } ?: true) && (searchText.isEmpty() || it.third.contains(searchText,true) || monthFormat.format(Date(it.second)).contains(searchText,true)) },true)
   1 -> {
    val scroll=ScrollView(this).apply { isFillViewport=true }
    val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(12),dp(12),dp(20)) }
    list.addView(textView("Alben",26f).apply { setPadding(dp(8),0,0,dp(16)) })
    val albumList=(listOf("Alle Videos" to allMedia.filter { videoUris.contains(it.first) })+albums.toList().sortedByDescending { it.second.size })
    albumList.chunked(2).forEach { pair ->
     val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
     pair.forEach { (name,items) ->
      val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(5),dp(5),dp(5),dp(14)); setOnClickListener { savedGridPosition=0; if(name=="Alle Videos") { showGrid(items,false) } else { albumFilter=name; showTab(0) } } }
      val preview=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; background=rounded(Color.rgb(32,28,47),15) }
      preview.clipToOutline=true
      card.addView(preview,LinearLayout.LayoutParams(-1,dp(155)))
      card.addView(textView(name,16f).apply { setSingleLine(true); ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(3),dp(8),dp(3),0) })
      card.addView(textView(items.size.toString()+" Elemente",12f).apply { setTextColor(Color.LTGRAY); setPadding(dp(3),0,0,0) })
      row.addView(card,LinearLayout.LayoutParams(0,-2,1f))
      val uri=items.maxByOrNull { it.second }?.first
      if(uri!=null) {
       val key=uri.toString()
       val cached=cache.get(key)
       if(cached!=null) preview.setImageBitmap(cached)
       else executor.execute { try {
        val bitmap=contentResolver.loadThumbnail(uri,android.util.Size(400,400),null)
        cache.put(key,bitmap)
        runOnUiThread { preview.setImageBitmap(bitmap) }
       } catch(_:Exception) {} }
      }
     }
     if(pair.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
     list.addView(row)
    }
    scroll.addView(list); galleryBody.addView(scroll)
   }
   2 -> {
    val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(15),dp(20),0) }
    list.addView(textView("Sammlungen",26f))
    val fav=allMedia.filter { favorites.getBoolean(it.first.toString(),false) }
    list.addView(textView("♥   Favoriten ("+fav.size+")",20f).apply { setPadding(0,dp(25),0,dp(25)); setOnClickListener { showGrid(fav,false) } })
    list.addView(textView("▶   Alle Videos ("+videoUris.size+")",20f).apply { setPadding(0,dp(25),0,dp(25)); setOnClickListener { showGrid(allMedia.filter { videoUris.contains(it.first) },false) } })
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
   onItemClickListener=AdapterView.OnItemClickListener { _,_,position,_ -> savedGridPosition=firstVisiblePosition; viewer(items[position]) }
  }
  row.addView(grid,LinearLayout.LayoutParams(0,-1,1f))
  if(timeline && savedGridPosition>0) grid.post { grid.setSelection(savedGridPosition.coerceAtMost((items.size-1).coerceAtLeast(0))) }
  if(timeline) {
   val months=items.map { monthFormat.format(Date(it.second)) }
   val markers=months.withIndex().filter { it.index==0 || it.value!=months[it.index-1] }.map { it.value to it.index }
   val side=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=android.view.Gravity.CENTER; background=rounded(Color.rgb(22,19,35),15) }
   val current=textView(markers.firstOrNull()?.first?:"Jahr\nMonat",11f).apply { gravity=android.view.Gravity.CENTER }
   side.addView(current,LinearLayout.LayoutParams(dp(74),dp(78)))
   val seek=SeekBar(this).apply { rotation=270f; max=(markers.size-1).coerceAtLeast(0); progressTintList=android.content.res.ColorStateList.valueOf(accent); thumbTintList=android.content.res.ColorStateList.valueOf(accent) }
   side.addView(seek,LinearLayout.LayoutParams(dp(74),dp(270)))
   seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(s:SeekBar?,value:Int,fromUser:Boolean) {
     if(fromUser && markers.isNotEmpty()) { val pair=markers[(markers.lastIndex-value).coerceIn(0,markers.lastIndex)]; current.text=pair.first; grid.setSelection(pair.second) }
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
     if(!seek.isPressed) seek.progress=markers.lastIndex-index
    }
   })
   row.addView(side,LinearLayout.LayoutParams(dp(82),-1))
  }
  galleryBody.addView(row)
 }
 private fun viewer(item:Triple<android.net.Uri,Long,String>) {
  showingViewer=true
  val viewerToken=++viewerGeneration
  var currentItem=item
  var currentIndex=visiblePhotos.indexOfFirst { it.first==currentItem.first }.coerceAtLeast(0)
  var startX=0f
  var startY=0f
  var requestId=0
  val screen=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); fitsSystemWindows=true }
  val heading=textView("‹    "+format.format(Date(currentItem.second)),19f).apply { setPadding(dp(18),0,0,0); setOnClickListener { showingViewer=false; viewerGeneration++; setContentView(host); showTab(activeTab) } }
  screen.addView(heading,LinearLayout.LayoutParams(-1,dp(60)))
  var actionsVisible=true
  var bottomActions:LinearLayout?=null
  val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
  val mediaFrame=FrameLayout(this)
  val player=VideoView(this).apply { visibility=View.GONE }
  val playback=MediaController(this)
  player.setMediaController(playback)
  mediaFrame.addView(image,FrameLayout.LayoutParams(-1,-1))
  mediaFrame.addView(player,FrameLayout.LayoutParams(-1,-1))
  fun stopPlayback() {
   playback.hide()
   player.setOnPreparedListener(null)
   player.stopPlayback()
   player.visibility=View.GONE
   image.visibility=View.VISIBLE
  }
  player.setOnErrorListener { _,_,_ ->
   android.widget.Toast.makeText(this@MainActivity,"Video kann nicht abgespielt werden",android.widget.Toast.LENGTH_SHORT).show()
   stopPlayback()
   true
  }
  var panX=0f
  var panY=0f
  var lastTouchX=0f
  var lastTouchY=0f
  var dragging=false
  var switchingPhoto=false
  val pinch=android.view.ScaleGestureDetector(this,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
   override fun onScale(detector:android.view.ScaleGestureDetector):Boolean {
    image.scaleX=(image.scaleX*detector.scaleFactor).coerceIn(1f,4f)
    image.scaleY=image.scaleX
    if(image.scaleX<=1.01f) { panX=0f; panY=0f; image.translationX=0f; image.translationY=0f }
    return true
   }
  })
  var refreshFavorite: (() -> Unit)?=null
  fun showPhoto(photo:Triple<android.net.Uri,Long,String>) {
   stopPlayback()
   currentItem=photo
   image.scaleX=1f; image.scaleY=1f; image.translationX=0f; image.translationY=0f; panX=0f; panY=0f
   refreshFavorite?.invoke()
   heading.text="‹    "+format.format(Date(photo.second))
   image.setImageDrawable(null)
   val id=++requestId
   if(videoUris.contains(photo.first)) {
    player.visibility=View.VISIBLE
    image.visibility=View.GONE
    player.setOnPreparedListener { mp ->
     if(showingViewer && viewerToken==viewerGeneration && id==requestId) {
      mp.isLooping=false
      player.start()
      playback.show(3000)
     }
    }
    player.setVideoURI(photo.first)
    return
   }
   executor.execute { try {
    val uri=photo.first
    val bitmap=if(contentResolver.getType(uri)?.startsWith("video/")==true) {
     contentResolver.loadThumbnail(uri,android.util.Size(1600,1600),null)
    } else {
     try {
      val source=android.graphics.ImageDecoder.createSource(contentResolver,uri)
      android.graphics.ImageDecoder.decodeBitmap(source) { decoder,info,_ ->
       val width=info.size.width.coerceAtLeast(1)
       val height=info.size.height.coerceAtLeast(1)
       var sample=1
       while(width/sample>4096 || height/sample>4096 || width.toLong()*height.toLong()/(sample.toLong()*sample)>16000000L) sample*=2
       decoder.setTargetSampleSize(sample)
       decoder.allocator=android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
      }
     } catch(_:Exception) {
      contentResolver.loadThumbnail(uri,android.util.Size(2000,2000),null)
     }
    }
    runOnUiThread { if(showingViewer && viewerToken==viewerGeneration && id==requestId) image.setImageBitmap(bitmap) }
   } catch(_:Exception) {} }
  }
  image.setOnTouchListener { _,event ->
   pinch.onTouchEvent(event)
   if(pinch.isInProgress || event.pointerCount>1) { dragging=false; return@setOnTouchListener true }
   when(event.actionMasked) {
    MotionEvent.ACTION_DOWN -> { if(switchingPhoto) return@setOnTouchListener true; startX=event.x; startY=event.y; lastTouchX=event.x; lastTouchY=event.y; dragging=false; true }
    MotionEvent.ACTION_MOVE -> {
     if(switchingPhoto) return@setOnTouchListener true
     if(image.scaleX>1.01f) {
      val dx=event.x-lastTouchX
      val dy=event.y-lastTouchY
      if(kotlin.math.abs(dx)>1 || kotlin.math.abs(dy)>1) dragging=true
      val limitX=image.width*(image.scaleX-1f)/2f
      val limitY=image.height*(image.scaleY-1f)/2f
      panX=(panX+dx).coerceIn(-limitX,limitX)
      panY=(panY+dy).coerceIn(-limitY,limitY)
      image.translationX=panX
      image.translationY=panY
     }
     lastTouchX=event.x; lastTouchY=event.y
     true
    }
    MotionEvent.ACTION_UP -> {
     if(switchingPhoto) return@setOnTouchListener true
     val dx=event.x-startX
     val dy=event.y-startY
     if(image.scaleX<=1.01f && kotlin.math.abs(dx)>dp(65) && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.3f) {
      val next=currentIndex+if(dx<0)1 else -1
      if(next in visiblePhotos.indices) {
       switchingPhoto=true
       val direction=if(dx<0)-1 else 1
       image.animate().cancel()
       image.animate().translationX(direction*dp(110).toFloat()).alpha(0.25f).setDuration(110).withEndAction {
        currentIndex=next
        showPhoto(visiblePhotos[next])
        image.translationX=(-direction*dp(90)).toFloat()
        image.alpha=0.25f
        image.animate().translationX(0f).alpha(1f).setDuration(180).withEndAction { switchingPhoto=false }.start()
       }.start()
      } else {
       image.animate().translationX(0f).setDuration(130).start()
      }
     } else if(!dragging && kotlin.math.abs(dx)<dp(20) && kotlin.math.abs(dy)<dp(20)) {
      actionsVisible=!actionsVisible
      bottomActions?.visibility=if(actionsVisible)View.VISIBLE else View.GONE
      heading.visibility=if(actionsVisible)View.VISIBLE else View.INVISIBLE
     }
     true
    }
    else -> true
   }
  }
  screen.addView(mediaFrame,LinearLayout.LayoutParams(-1,0,1f))
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
  playback.setAnchorView(player)
  showPhoto(item)
 }
 private fun search() {
  val input=EditText(this).apply { hint="Dateiname oder Monat"; setSingleLine(true) }
  android.app.AlertDialog.Builder(this).setTitle("Fotos suchen").setView(input).setPositiveButton("Suchen") { _,_ -> searchText=input.text.toString(); albumFilter=null; showTab(0) }.setNegativeButton("Abbrechen",null).show()
 }
 private fun chooseGridSize() {
  val options=arrayOf("3 Bilder pro Reihe","4 Bilder pro Reihe","5 Bilder pro Reihe","6 Bilder pro Reihe")
  android.app.AlertDialog.Builder(this).setTitle("Rastergröße").setSingleChoiceItems(options,(gridColumns-3).coerceIn(0,3)) { dialog,choice ->
   gridColumns=choice+3
   savedGridPosition=0
   dialog.dismiss()
   showTab(0)
  }.setNegativeButton("Abbrechen",null).show()
 }
 private fun menu() {
  android.app.AlertDialog.Builder(this).setItems(arrayOf("Suchen","Aktualisieren","Rastergröße ändern","Alle Fotos")) { _,i ->
   when(i) { 0->search(); 1->showGallery(); 2->chooseGridSize(); else->{ albumFilter=null; searchText=""; savedGridPosition=0; showTab(0) } }
  }.show()
 }
}
