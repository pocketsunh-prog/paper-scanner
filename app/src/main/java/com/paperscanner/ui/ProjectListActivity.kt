package com.paperscanner.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.paperscanner.R
import com.paperscanner.data.Project
import com.paperscanner.data.ProjectManager
import java.text.SimpleDateFormat
import java.util.*

class ProjectListActivity : AppCompatActivity() {

    private lateinit var projectManager: ProjectManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: ProjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_list)

        projectManager = ProjectManager(this)

        recyclerView = findViewById(R.id.recycler_projects)
        emptyView = findViewById(R.id.empty_view)

        adapter = ProjectAdapter(
            onProjectClick = { project ->
                val intent = Intent(this, ImageEditorActivity::class.java)
                intent.putExtra("project_id", project.id)
                startActivity(intent)
            },
            onProjectDelete = { project ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_project)
                    .setMessage("Delete \"${project.name}\" and all its images?")
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        projectManager.deleteProject(project.id)
                        refreshProjects()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add_project).setOnClickListener {
            showNewProjectDialog()
        }

        findViewById<FloatingActionButton>(R.id.fab_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProjects()
    }

    private fun refreshProjects() {
        val projects = projectManager.listProjects()
        adapter.submitList(projects)
        emptyView.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.enter_project_name)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.new_project)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (projectManager.projectNameExists(name)) {
                    Toast.makeText(this, R.string.project_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val project = Project(name = name)
                projectManager.saveProject(project)
                refreshProjects()

                val intent = Intent(this, ImageEditorActivity::class.java)
                intent.putExtra("project_id", project.id)
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class ProjectAdapter(
        private val onProjectClick: (Project) -> Unit,
        private val onProjectDelete: (Project) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.ViewHolder>() {

        private var projects: List<Project> = emptyList()
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        fun submitList(list: List<Project>) {
            projects = list
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameView: android.widget.TextView = view.findViewById(R.id.project_name)
            val dateView: android.widget.TextView = view.findViewById(R.id.project_date)
            val countView: android.widget.TextView = view.findViewById(R.id.image_count)
            val deleteBtn: View = view.findViewById(R.id.btn_delete_project)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val project = projects[position]
            holder.nameView.text = project.name
            holder.dateView.text = dateFormat.format(Date(project.createdAt))
            holder.countView.text = "${project.images.size} images"
            holder.itemView.setOnClickListener { onProjectClick(project) }
            holder.deleteBtn.setOnClickListener { onProjectDelete(project) }
        }

        override fun getItemCount() = projects.size
    }
}
