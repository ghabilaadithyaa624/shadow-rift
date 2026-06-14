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
