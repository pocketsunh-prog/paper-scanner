package com.paperscanner.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ScanImage(
    val id: String = System.currentTimeMillis().toString() + (Math.random() * 1000).toInt(),
    val filePath: String,
    val timestamp: Long = System.currentTimeMillis(),
    var filterMode: ImageFilterMode = ImageFilterMode.COLOR,
    var rotation: Int = 0
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("filePath", filePath)
            put("timestamp", timestamp)
            put("filterMode", filterMode.name)
            put("rotation", rotation)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ScanImage {
            return ScanImage(
                id = json.getString("id"),
                filePath = json.getString("filePath"),
                timestamp = json.getLong("timestamp"),
                filterMode = ImageFilterMode.valueOf(json.optString("filterMode", "COLOR")),
                rotation = json.optInt("rotation", 0)
            )
        }
    }
}

enum class ImageFilterMode {
    COLOR, GRAYSCALE, BLACK_WHITE
}

data class Project(
    val id: String = System.currentTimeMillis().toString(),
    var name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val images: MutableList<ScanImage> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val jsonArray = JSONArray()
        images.forEach { jsonArray.put(it.toJson()) }
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("createdAt", createdAt)
            put("images", jsonArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Project {
            val imagesArray = json.getJSONArray("images")
            val images = mutableListOf<ScanImage>()
            for (i in 0 until imagesArray.length()) {
                images.add(ScanImage.fromJson(imagesArray.getJSONObject(i)))
            }
            return Project(
                id = json.getString("id"),
                name = json.getString("name"),
                createdAt = json.getLong("createdAt"),
                images = images
            )
        }
    }
}
