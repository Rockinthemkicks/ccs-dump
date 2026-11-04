package com.example.ccssync

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** On-device text recognition (bundled model, works offline). Call off the main thread. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    class Result(val words: List<TripParser.Word>, val imageWidth: Int)

    fun read(context: Context, uri: Uri): Result {
        val image = InputImage.fromFilePath(context, uri)
        val text = Tasks.await(recognizer.process(image))
        val words = ArrayList<TripParser.Word>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val b = el.boundingBox ?: continue
                    words.add(TripParser.Word(el.text, b.left, b.top, b.right, b.bottom))
                }
            }
        }
        return Result(words, image.width)
    }
}

/** Airport code -> time zone, from the bundled assets/airport_tz.csv. */
object AirportZones {
    @Volatile private var map: Map<String, String>? = null

    fun get(context: Context): TripParser.Airports {
        val m = map ?: synchronized(this) {
            map ?: context.assets.open("airport_tz.csv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val i = line.indexOf(',')
                    if (i == 3) line.substring(0, 3) to line.substring(4).trim() else null
                }.toMap()
            }.also { map = it }
        }
        return TripParser.Airports { code -> m[code] }
    }
}
