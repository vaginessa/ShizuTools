package com.legendsayantan.adbtools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.legendsayantan.adbtools.adapters.DebloatAdapter
import com.legendsayantan.adbtools.adapters.SimpleAdapter
import com.legendsayantan.adbtools.data.AppData
import com.legendsayantan.adbtools.lib.ShizukuRunner
import com.legendsayantan.adbtools.lib.Utils.Companion.extractUrls
import com.legendsayantan.adbtools.lib.Utils.Companion.getAllInstalledApps
import com.legendsayantan.adbtools.lib.Utils.Companion.initialiseStatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.net.URL


class DebloatActivity : AppCompatActivity() {
    val output = listOf<String>()
    val prefs by lazy { getSharedPreferences("debloater", Context.MODE_PRIVATE) }
    lateinit var apps: HashMap<String,AppData>
    lateinit var filterBtn: ImageView
    lateinit var list: ListView
    var filterMode = false
    lateinit var cachedApps: HashMap<String,AppData>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debloat)
        initialiseStatusBar()
        list = findViewById<ListView>(R.id.apps_list)
        ShizukuRunner.runAdbCommand("pm grant $packageName android.permission.QUERY_ALL_PACKAGES",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {}
            })
        val setListListener = {
            list.setOnItemClickListener { _, _, position, _ ->
                var dialog: AlertDialog? = null
                val id = apps.keys.toList()[position]
                val app = apps.values.toList()[position]
                //show dialog
                val links = MaterialTextView(this)
                links.text = "Links :"
                val listOfLinks = app.description.extractUrls()
                val linkView = ListView(this)
                linkView.adapter =
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, listOfLinks)
                linkView.setOnItemClickListener { _, _, position, _ ->
                    //open link
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(listOfLinks[position])))
                }
                val uninstallBtn = MaterialButton(this)
                uninstallBtn.text = "Confirm to uninstall"
                uninstallBtn.setOnClickListener {
                    //uninstall app
                    Toast.makeText(this, "Uninstalling ${app.name}", Toast.LENGTH_SHORT).show()
                    ShizukuRunner.runAdbCommand("cmd package uninstall -k --user 0 ${id}",
                        object : ShizukuRunner.CommandResultListener {
                            override fun onCommandResult(output: String, done: Boolean) {
                                if (done) {
                                    runOnUiThread {
                                        if (output.contains("Success", true)) {
                                            apps = apps.filterKeys { it != id } as HashMap<String,AppData>
                                            list.adapter =
                                                DebloatAdapter(this@DebloatActivity, apps)
                                            Toast.makeText(
                                                applicationContext,
                                                "Uninstalled ${app.name}",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                applicationContext,
                                                "Failed to uninstall ${app.name},\n$output",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        dialog?.dismiss()
                                    }
                                }
                            }
                        })
                }
                val btnContainer = LinearLayout(this)
                btnContainer.addView(uninstallBtn)
                btnContainer.setPadding(20, 20, 20, 20)
                btnContainer.gravity = Gravity.RIGHT
                val dialogView = LinearLayout(this)
                dialogView.orientation = LinearLayout.VERTICAL
                dialogView.setPadding(20, 0, 20, 20)
                if (listOfLinks.isNotEmpty()) dialogView.addView(links)
                dialogView.addView(linkView)
                dialogView.addView(btnContainer)
                dialog = MaterialAlertDialogBuilder(this)
                    .setView(dialogView)
                    .setCancelable(true)
                    .setTitle(app.name)
                    .setMessage(
                        "package: ${id}\n${
                            when (app.removal.ifEmpty { "X" }[0]) {
                                'R' -> "Recommended to uninstall"
                                'A' -> "Only advanced users should uninstall"
                                'E' -> "Only expert users should uninstall"
                                'U' -> "Unsafe to uninstall"
                                else -> "No info, uninstall at own risk"
                            }
                        }."
                    )
                    .show()
            }
            findViewById<LinearLayout>(R.id.loader).visibility = LinearLayout.GONE
            filterBtn = findViewById(R.id.imageSearch)
            filterBtn.setOnClickListener { filter() }
        }

        if (output.isEmpty()) {
            //success
            info("Loading online database")
            loadDatabase({ databaseApps ->
                //database available
                runOnUiThread {
                    info("Scanning local apps")
                    val localApps = packageManager.getAllInstalledApps()
                    val finalApp = hashMapOf<String,AppData>()
                    Thread {
                        localApps.forEach { app ->
                            val searchResult = databaseApps[app.packageName]
                            val appName = app.loadLabel(packageManager).toString()

                            if (searchResult != null) {
                                if (searchResult.removal != "Unsafe") finalApp[app.packageName] = searchResult.apply {
                                    name = appName
                                }
                            } else {
                                finalApp[app.packageName] =
                                    AppData(
                                        appName, "",
                                        "", arrayListOf(), arrayListOf(),
                                        arrayListOf(), ""
                                    )
                            }
                        }
                        runOnUiThread {
                            apps = finalApp.entries.sortedWith(compareBy { it.value.name })
                                .associate { it.key to it.value } as HashMap<String,AppData>
                            list.adapter = DebloatAdapter(this@DebloatActivity, apps)
                            setListListener()
                        }
                    }.start()
                }
            }, {
                info("No local or online database found.\nPlease check your internet connection and try again.")
            })
        }

        findViewById<ImageView>(R.id.imageRestore).setOnClickListener { restoreMode() }
    }

    private fun loadDatabase(onComplete: (HashMap<String,AppData>) -> Unit, onFailure: () -> Unit) {
        try {
            val url =
                URL("https://cdn.jsdelivr.net/gh/Universal-Debloater-Alliance/universal-android-debloater-next-generation@main/resources/assets/uad_lists.json")
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    // Read the JSON content from the URL
                    val connection = url.openConnection()
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val jsonContent = reader.readText()
                    prefs.edit().putString("database", jsonContent).apply()
                    onComplete(jsonContent.asAppDatabase())
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (prefs.getString("database", null).isNullOrBlank()) {
                        onFailure()
                    } else {
                        onComplete(prefs.getString("database", null)!!.asAppDatabase())
                    }
                }
            }
        } catch (e: Exception) {
            if (prefs.getString("database", null).isNullOrBlank()) {
                onFailure()
            } else {
                onComplete(prefs.getString("database", null)!!.asAppDatabase())
            }
        }
    }

    private fun String.asAppDatabase(): HashMap<String,AppData> {
        val type = object : TypeToken<HashMap<String,AppData?>?>() {}.type
        return GsonBuilder()
            .registerTypeAdapter(type, object : JsonDeserializer<HashMap<String, AppData>> {
                override fun deserialize(
                    json: JsonElement?,
                    typeOfT: Type?,
                    context: JsonDeserializationContext?
                ): HashMap<String, AppData> {
                    val map = HashMap<String, AppData>()
                    json?.asJsonObject?.entrySet()?.forEach {
                        map[it.key] = context?.deserialize(it.value, AppData::class.java)!!
                    }
                    return map
                }
            })
            .create()
            .fromJson(this, type)
    }


    fun info(string: String) {
        findViewById<TextView>(R.id.infoView).text = string
    }

    private fun filter() = if (filterMode) {
        filterMode = false
        apps = cachedApps
        list.adapter =
            DebloatAdapter(this@DebloatActivity, apps)
        filterBtn.setImageResource(R.drawable.baseline_filter_list_24)
    } else {
        val layout = TextInputLayout(this)
        val editText = TextInputEditText(this)
        editText.hint = "Enter app name/package/severity/type"
        layout.addView(editText)
        layout.setPadding(50,0,50,0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setCancelable(true)
            .setTitle("Filter")
            .setPositiveButton(
                "Apply"
            ) { dialog, which ->
                filterMode = true
                val t = editText.text.toString()
                cachedApps = apps
                apps = apps.filterValues { appData->
                    appData.name.contains(t)
                            || appData.removal.contains(t)
                            || appData.description.contains(t)
                            || appData.list.contains(t)
                } as HashMap<String,AppData>
                list.adapter =
                    DebloatAdapter(this@DebloatActivity, apps)
                dialog.dismiss()
                filterBtn.setImageResource(R.drawable.baseline_filter_list_off_24)
            }
            .show()
    }

    fun restoreMode() {
        val activityContext = this
        ShizukuRunner.runAdbCommand("cmd package list packages -u",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) {
                        val allApps = output.replace("package:", "").split("\n");
                        ShizukuRunner.runAdbCommand("cmd package list packages",
                            object : ShizukuRunner.CommandResultListener {
                                override fun onCommandResult(output: String, done: Boolean) {
                                    val installed = output.replace("package:", "").split("\n")
                                    if (done) {
                                        val uninstalled = allApps.filter { !installed.contains(it) }
                                        println(uninstalled)
                                        runOnUiThread {
                                            val appsView = RecyclerView(activityContext)
                                            val dialog = MaterialAlertDialogBuilder(activityContext)
                                                .setView(appsView)
                                                .setCancelable(true)
                                                .setTitle("Restore uninstalled apps")
                                                .setMessage(
                                                    "Select the app to start restoration."
                                                )
                                                .show()
                                            appsView.layoutManager =
                                                LinearLayoutManager(activityContext)
                                            appsView.adapter =
                                                SimpleAdapter(uninstalled) {
                                                    ShizukuRunner.runAdbCommand("cmd package install-existing ${uninstalled[it]}",
                                                        object :
                                                            ShizukuRunner.CommandResultListener {
                                                            override fun onCommandResult(
                                                                output: String,
                                                                done: Boolean
                                                            ) {
                                                                if (done) {
                                                                    runOnUiThread {
                                                                        Toast.makeText(
                                                                            activityContext,
                                                                            output,
                                                                            Toast.LENGTH_LONG
                                                                        ).show()
                                                                    }
                                                                }
                                                            }
                                                        })
                                                    dialog.dismiss()
                                                }
                                        }
                                    }
                                }
                            })
                    }
                }
            })
    }
}