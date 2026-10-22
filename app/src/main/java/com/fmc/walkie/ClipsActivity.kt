package com.fmc.walkie

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class ClipsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private val clips = ArrayList<File>()
    private val labels = ArrayList<String>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        listView = findViewById(R.id.clips_list)
        emptyView = findViewById(R.id.empty)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> clips.getOrNull(position)?.let { options(it) } }
        load()
    }

    private fun load() {
        val dir = File(getExternalFilesDir(null), "clips")
        clips.clear()
        labels.clear()
        dir.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { folder ->
                val frames = folder.listFiles { file -> file.name.endsWith(".jpg") }?.size ?: 0
                val name = folder.name.substringAfter('_', folder.name)
                clips.add(folder)
                labels.add("$name  ·  $frames frames")
            }
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (clips.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun options(folder: File) {
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(arrayOf("Play", "Save to Pictures", "Delete")) { _, which ->
                when (which) {
                    0 -> play(folder)
                    1 -> save(folder)
                    2 -> confirmDelete(folder)
                }
            }
            .show()
    }

    private fun frames(folder: File): List<File> {
        return folder.listFiles { file -> file.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()
    }

    private fun play(folder: File) {
        val files = frames(folder)
        if (files.isEmpty()) {
            Toast.makeText(this, "No frames in this clip", Toast.LENGTH_SHORT).show()
            return
        }
        val image = ImageView(this)
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(image)
            show()
        }
        val runner = object : Runnable {
            var index = 0
            override fun run() {
                if (!dialog.isShowing) return
                BitmapFactory.decodeFile(files[index % files.size].absolutePath)?.let { image.setImageBitmap(it) }
                index++
                handler.postDelayed(this, 110)
            }
        }
        handler.post(runner)
        dialog.setOnDismissListener { handler.removeCallbacks(runner) }
    }

    private fun save(folder: File) {
        val files = frames(folder)
        if (files.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }
        var saved = 0
        files.forEach { if (saveImage(it, folder.name)) saved++ }
        Toast.makeText(this, "Saved $saved images to Pictures/Sentry", Toast.LENGTH_LONG).show()
    }

    private fun saveImage(file: File, album: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "${album}_${file.name}")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sentry/$album")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sentry/$album")
                dir.mkdirs()
                File(dir, file.name).outputStream().use { out -> file.inputStream().use { it.copyTo(out) } }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun confirmDelete(folder: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete clip?")
            .setMessage("This removes ${folder.name} from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                folder.deleteRecursively()
                load()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
