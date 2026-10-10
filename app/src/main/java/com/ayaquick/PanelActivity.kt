package com.ayaquick

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The menu as its own screen, for the launcher icon. See-through, so whatever was open stays visible on the left. */
class PanelActivity : Activity(), Panel.Host {

    private lateinit var panel: Panel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)             // the sheet slides in by itself
        panel = Panel(this, this)
        setContentView(panel.root)
        handleTest(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleTest(intent)
    }

    override fun onResume() {
        super.onResume()
        front = this
        panel.onShown()
    }

    override fun onPause() {
        super.onPause()
        if (front === this) front = null
        panel.onHidden()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()     // nothing is kept around once the menu is out of sight
    }

    /** Slide the sheet out and finish, as B does. */
    fun dismiss() = panel.close()

    override fun closePanel() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun launch(intent: Intent): Boolean = try {
        startActivity(intent)
        closePanel()
        true
    } catch (t: Throwable) {
        false
    }

    /** adb hooks for development and screenshots. */
    private fun handleTest(i: Intent?) {
        when (i?.getStringExtra("test") ?: return) {
            "page" -> i.getStringExtra("p")?.let { name -> Panel.Page.values().firstOrNull { it.name.equals(name, true) }?.let { panel.goTo(it) } }
            "scroll" -> panel.scrollTo(i.getIntExtra("y", 0))
        }
    }

    companion object {
        /** The instance on screen, so the button shortcut closes it instead of opening a second menu on top. */
        var front: PanelActivity? = null
            private set
    }
}
