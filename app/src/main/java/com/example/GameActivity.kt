package com.example

import android.app.Dialog
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameActivity : ComponentActivity(), GameView.GameListener {

    private lateinit var gameView: GameView
    private lateinit var repository: ProgressionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Android 15 Edge-to-Edge window configure
        enableEdgeToEdge()

        // Absolute full-screen immersive landscape settings for maximum real estate
        hideSystemBars()

        // Keep screen active during action gameplay loops
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Room Database persistence channel
        val database = AppGameDatabase.getDatabase(this)
        repository = ProgressionRepository(database.progressionDao())

        // Primary Frame layout holding custom game view
        val rootLayout = FrameLayout(this)
        gameView = GameView(this)
        gameView.gameListener = this
        rootLayout.addView(gameView)

        setContentView(rootLayout)

        // Load meta-progression character skill upgrades before genesis
        lifecycleScope.launch {
            repository.initDatabaseIfNeeded()
            val skills = withContext(Dispatchers.IO) {
                database.progressionDao().getAllSkills()
            }
            gameView.startNewGame(skills)
        }

        // Handle Android 15 Predictive back gesture
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirmationDialog()
            }
        })
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
    }

    private fun showExitConfirmationDialog() {
        gameView.pause()
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_simple_cyber)
        dialog.setCancelable(false)

        val title = dialog.findViewById<TextView>(R.id.dialog_title)
        val subtitle = dialog.findViewById<TextView>(R.id.dialog_subtitle)
        val btnQuit = dialog.findViewById<Button>(R.id.btn_quit)
        val btnResume = dialog.findViewById<Button>(R.id.btn_resume)

        title.text = "ABORT EXPEDITION?"
        subtitle.text = "Quitting now will forfeit current session items."

        btnQuit.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        btnResume.setOnClickListener {
            dialog.dismiss()
            gameView.resume()
            hideSystemBars()
        }

        dialog.show()
    }

    /**
     * Intercepts Player death state. Triggers saving runs statistics and Essences asynchronously.
     */
    override fun onPlayerDeath(floorReached: Int, kills: Int, essenceEarned: Int) {
        // Save gathered essenses and stats before popping menu
        lifecycleScope.launch(Dispatchers.IO) {
            repository.awardEssenceAndSaveScore(essenceEarned, floorReached, kills)
            
            withContext(Dispatchers.Main) {
                showDeathDialog(floorReached, kills, essenceEarned)
            }
        }
    }

    private fun showDeathDialog(floor: Int, kills: Int, essence: Int) {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_simple_cyber)
        dialog.setCancelable(false)

        val title = dialog.findViewById<TextView>(R.id.dialog_title)
        val subtitle = dialog.findViewById<TextView>(R.id.dialog_subtitle)
        val btnQuit = dialog.findViewById<Button>(R.id.btn_quit)
        val btnResume = dialog.findViewById<Button>(R.id.btn_resume)

        title.text = "RIFT IMMOLATION"
        title.setTextColor(android.graphics.Color.RED)
        subtitle.text = "You expired on Floor $floor.\nEliminated: $kills Hostiles.\nSecured: +$essence Shadow Essence."

        btnResume.text = "TRY AGAIN"
        btnQuit.text = "RETURN TO MENU"

        btnQuit.setOnClickListener {
            dialog.dismiss()
            finish() // exit activity
        }

        btnResume.setOnClickListener {
            dialog.dismiss()
            hideSystemBars()
            // Pull skills again and start fresh run
            lifecycleScope.launch {
                val db = AppGameDatabase.getDatabase(this@GameActivity)
                val skills = withContext(Dispatchers.IO) {
                    db.progressionDao().getAllSkills()
                }
                gameView.startNewGame(skills)
            }
        }

        dialog.show()
    }

    override fun onLevelUp(level: Int, onStatSelected: (statType: Int) -> Unit) {
        runOnUiThread {
            showLevelUpDialog(level, onStatSelected)
        }
    }

    private fun showLevelUpDialog(level: Int, onStatSelected: (statType: Int) -> Unit) {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val context = this
        val root = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#E6050505")) // Translucent dark overlay
        }
        
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#121212")) // Dark panel
            setPadding(64, 48, 64, 48)
            gravity = android.view.Gravity.CENTER
            
            val lp = FrameLayout.LayoutParams(
                720, // Clean fixed width for landscape phone viewports
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
            layoutParams = lp
        }
        
        // Cyan glowing topline
        val topline = android.view.View(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#00FFFF"))
            val tLp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                8 // 8px thick
            )
            tLp.bottomMargin = 32
            layoutParams = tLp
        }
        container.addView(topline)
        
        // Title: UPGRADE INSTALLED
        val titleText = TextView(context).apply {
            text = "SYSTEM UPGRADE"
            setTextColor(android.graphics.Color.parseColor("#00FFFF"))
            textSize = 24f
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        container.addView(titleText)
        
        // Subtitle text
        val subtitleText = TextView(context).apply {
            text = "LEVEL $level REACHED. CHOOSE ROUTINE SPECIALIZATION:"
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 36)
        }
        container.addView(subtitleText)
        
        // Helper function to create cyber buttons inside Dialog
        fun createUpgradeButton(label: String, desc: String, selectedType: Int) {
            val btn = Button(context).apply {
                text = "$label\n$desc"
                setTextColor(android.graphics.Color.BLACK)
                setBackgroundColor(android.graphics.Color.parseColor("#00FFFF"))
                textSize = 15f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(24, 16, 24, 16)
                
                val bLp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                bLp.bottomMargin = 20
                layoutParams = bLp
                
                setOnClickListener {
                    dialog.dismiss()
                    onStatSelected(selectedType)
                    hideSystemBars()
                }
            }
            container.addView(btn)
        }

        createUpgradeButton("POWER ENCRYPTOR", "+15% BULLET ATTACK DAMAGE", 0)
        createUpgradeButton("STRUCTURE OVERCLOCK", "+20 MAX INTEGRITY (HP & HEAL)", 1)
        createUpgradeButton("MOMENTUM ACCELERATOR", "+10% RE-ROUTING MOVE SPEED", 2)

        root.addView(container)
        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.show()
    }

    override fun onVictory() {}

    override fun onResume() {
        super.onResume()
        gameView.resume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
