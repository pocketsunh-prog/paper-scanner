package com.paperscanner.data

import android.content.Context
import org.json.JSONArray
import java.io.File

class ProjectManager(private val context: Context) {

    private val baseDir: File
        get() = File(context.getExternalFilesDir(null), "Documents").also { it.mkdirs() }

    private val projectsFile: File
        get() = File(baseDir, "projects.json")

    fun getProjectsDir(): File = baseDir

    fun getProjectDir(projectId: String): File {
        return File(baseDir, projectId).also { it.mkdirs() }
    }

    fun listProjects(): List<Project> {
        if (!projectsFile.exists()) return emptyList()
        return try {
            val content = projectsFile.readText()
            val jsonArray = JSONArray(content)
            val projects = mutableListOf<Project>()
            for (i in 0 until jsonArray.length()) {
                projects.add(Project.fromJson(jsonArray.getJSONObject(i)))
            }
            projects.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveProject(project: Project) {
        val projects = listProjects().toMutableList()
        val index = projects.indexOfFirst { it.id == project.id }
        if (index >= 0) {
            projects[index] = project
        } else {
            projects.add(0, project)
        }
        persistProjects(projects)
    }

    fun deleteProject(projectId: String) {
        val dir = getProjectDir(projectId)
        if (dir.exists()) dir.deleteRecursively()
        val projects = listProjects().filter { it.id != projectId }
        persistProjects(projects)
    }

    fun getProject(projectId: String): Project? {
        return listProjects().find { it.id == projectId }
    }

    fun projectNameExists(name: String): Boolean {
        return listProjects().any { it.name.equals(name, ignoreCase = true) }
    }

    private fun persistProjects(projects: List<Project>) {
        val jsonArray = JSONArray()
        projects.forEach { jsonArray.put(it.toJson()) }
        projectsFile.writeText(jsonArray.toString(2))
    }

    fun generateImageFile(projectId: String): File {
        val dir = getProjectDir(projectId)
        return File(dir, "img_${System.currentTimeMillis()}.jpg")
    }
}
