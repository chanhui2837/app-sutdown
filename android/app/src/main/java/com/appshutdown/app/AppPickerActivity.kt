package com.appshutdown.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** AppBlock처럼 설치된 앱 목록에서 차단할 앱을 골라서 돌려준다. */
class AppPickerActivity : AppCompatActivity() {

    data class AppInfo(val label: String, val pkg: String, val icon: Drawable)

    private lateinit var adapter: AppAdapter
    private var all: List<AppInfo> = emptyList()
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        selected.addAll(intent.getStringArrayListExtra("selected") ?: emptyList())

        val etSearch: EditText = findViewById(R.id.etSearch)
        val rv: RecyclerView = findViewById(R.id.rvApps)
        val btnDone: Button = findViewById(R.id.btnDone)
        val tvCount: TextView = findViewById(R.id.tvCount)

        adapter = AppAdapter(selected) { tvCount.text = "선택됨: ${selected.size}개" }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        tvCount.text = "선택됨: ${selected.size}개"

        Thread { loadApps() }.start()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                adapter.filter(s.toString(), all)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnDone.setOnClickListener {
            val pkgs = ArrayList(selected)
            val labels = ArrayList(pkgs.map { p -> all.find { it.pkg == p }?.label ?: p })
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra("picked", pkgs)
                    .putStringArrayListExtra("pickedLabels", labels)
            )
            finish()
        }
    }

    private fun loadApps() {
        val pm: PackageManager = packageManager
        val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != packageName }
            .map { ai ->
                AppInfo(
                    pm.getApplicationLabel(ai)?.toString() ?: ai.packageName,
                    ai.packageName,
                    pm.getApplicationIcon(ai)
                )
            }
            .sortedBy { it.label.lowercase() }
        runOnUiThread {
            all = list
            adapter.submit(list)
        }
    }

    class AppAdapter(
        val selected: MutableSet<String>,
        val onToggle: () -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {
        private var items: List<AppInfo> = emptyList()
        fun submit(l: List<AppInfo>) { items = l; notifyDataSetChanged() }
        fun filter(q: String, src: List<AppInfo>) {
            items = if (q.isBlank()) src else src.filter {
                it.label.contains(q, true) || it.pkg.contains(q, true)
            }
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.ivIcon)
            val tvName: TextView = v.findViewById(R.id.tvAppName)
            val tvPkg: TextView = v.findViewById(R.id.tvPkg)
            val cb: CheckBox = v.findViewById(R.id.cbPick)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_app, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val a = items[pos]
            h.iv.setImageDrawable(a.icon)
            h.tvName.text = a.label
            h.tvPkg.text = a.pkg
            h.cb.setOnCheckedChangeListener(null)
            h.cb.isChecked = selected.contains(a.pkg)
            h.cb.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(a.pkg) else selected.remove(a.pkg)
                onToggle()
            }
            h.itemView.setOnClickListener { h.cb.toggle() }
        }
    }
}
