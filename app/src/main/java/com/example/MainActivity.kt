package com.example

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repository: ProgressionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize PerformanceManager
        PerformanceManager.init(this)

        // Initialize Database connections
        val database = AppGameDatabase.getDatabase(this)
        repository = ProgressionRepository(database.progressionDao())

        // Pre-populate skills table asynchronously if first boot
        lifecycleScope.launch {
            repository.initDatabaseIfNeeded()
        }

        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0A0A0A) // Sophisticated dark background
                ) { innerPadding ->
                    var isOptimizing by remember { mutableStateOf(!PerformanceManager.isOptimizingAtStartupDone) }

                    if (isOptimizing) {
                        // Show brief loading screen of device-optimization
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF050505)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "SHADOW RIFT",
                                    color = Color(0xFF00FFFF),
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 4.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                CircularProgressIndicator(
                                    color = Color(0xFF00FFFF),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = "Optimizing for your device...",
                                    color = Color(0xFFFF3B3B),
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Detecting Memory & Core allocations...",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(1800) // Brief loading presentation as requested
                            PerformanceManager.isOptimizingAtStartupDone = true
                            isOptimizing = false
                        }
                    } else {
                        MainGameDashboard(
                            repository = repository,
                            modifier = Modifier.padding(innerPadding),
                            onLaunchGame = {
                                val intent = Intent(this@MainActivity, GameActivity::class.java)
                                startActivity(intent)
                            },
                            onPlayClickHaptic = {
                                triggerTickHaptics()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun triggerTickHaptics() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30)
            }
        }
    }
}

@Composable
fun MainGameDashboard(
    repository: ProgressionRepository,
    modifier: Modifier = Modifier,
    onLaunchGame: () -> Unit,
    onPlayClickHaptic: () -> Unit
) {
    // Collect reactive statistics from Room DB
    val modelSkills by repository.allSkillsFlow.collectAsState(initial = emptyList())
    val playerProfile by repository.profileFlow.collectAsState(initial = PlayerProfile())
    
    val coroutineScope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val gridSize = 36.dp.toPx()
                val gridColor = Color(0xFF141414)
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += gridSize
                }
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = gridColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += gridSize
                }
            }
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // --- LEFT COLUMN: MENU ACTIONS & STATS (Weight 4f) ---
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(4f)
                .fillMaxHeight()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title Header Details
            Column {
                Text(
                    text = "SHADOW RIFT",
                    color = Color(0xFF00FFFF), // Intense Neon Cyan
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "TACTICAL ROGUELITE RUNTIME v1.0",
                    color = Color(0xFFFF3B3B), // Cyber Red Accent
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Stats Dashboard Container
                Text(
                    text = "EXPEDITION INTELLIGENCE",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatRow("EXPEDITIONS INITIATED", "${playerProfile?.runsCount ?: 0}")
                    StatRow("RIPS CLEARED (KILLS)", "${playerProfile?.totalKills ?: 0}")
                    StatRow("MAX DEPTH PENETRATED", "FLOOR ${playerProfile?.highestFloor ?: 1}")
                }

                // Performance settings config component
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "PERFORMANCE CORE",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                val context = androidx.compose.ui.platform.LocalContext.current
                var activeTier by remember { mutableStateOf(PerformanceManager.selectedTier) }
                val targetDetails = when (PerformanceManager.detectedTier) {
                    MemoryTier.LOW -> "3GB"
                    MemoryTier.MID -> "4-5GB"
                    MemoryTier.HIGH -> "6GB+"
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DETECTED RAM TIER:",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${PerformanceManager.detectedTier} ($targetDetails target)",
                            color = Color(0xFF00FFFF),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ACTIVE PROFILE:",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "$activeTier" + (if (PerformanceManager.isManualOverrideActive(context)) " (MANUAL)" else " (AUTO)"),
                            color = if (PerformanceManager.isManualOverrideActive(context)) Color(0xFFFFD700) else Color(0xFF00FF66),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "MANUALLY OVERRIDE TIER:",
                        color = Color.DarkGray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(MemoryTier.LOW, MemoryTier.MID, MemoryTier.HIGH).forEach { tier ->
                            val isSelected = activeTier == tier && PerformanceManager.isManualOverrideActive(context)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSelected) Color(0xFF00FFFF).copy(alpha = 0.15f) else Color(0xFF1B1B1B),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF00FFFF) else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        onPlayClickHaptic()
                                        PerformanceManager.setManualTier(context, tier)
                                        activeTier = PerformanceManager.selectedTier
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tier.name,
                                    color = if (isSelected) Color(0xFF00FFFF) else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Auto selector option
                        val isAuto = !PerformanceManager.isManualOverrideActive(context)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isAuto) Color(0xFF00FF66).copy(alpha = 0.15f) else Color(0xFF1B1B1B),
                                    RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isAuto) Color(0xFF00FF66) else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable {
                                    onPlayClickHaptic()
                                    PerformanceManager.setManualTier(context, null)
                                    activeTier = PerformanceManager.selectedTier
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "AUTO",
                                color = if (isAuto) Color(0xFF00FF66) else Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Launch Platform Button
            Button(
                onClick = {
                    onPlayClickHaptic()
                    onLaunchGame()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(2.dp, Color(0xFF00FFFF), RoundedCornerShape(16.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x1800FFFF),
                    contentColor = Color(0xFF00FFFF)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "ENTER CYBER RIFT",
                    color = Color(0xFF00FFFF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }

        // --- RIGHT COLUMN: META-PROGRESSION SKILL TREE (Weight 6f) ---
        Column(
            modifier = Modifier
                .weight(6f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header for Skill Core
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TALENT COGNITIVE MATRIX",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                // Available Shadow Essence Counter
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF161616), RoundedCornerShape(20.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⚡ ${playerProfile?.essence ?: 0} ESSENCE",
                        color = Color(0xFFFFD700),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Lazy scroll list of upgradable nodes
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(modelSkills) { skill ->
                    SkillNodeCard(
                        skill = skill,
                        playerEssence = playerProfile?.essence ?: 0,
                        onUpgradeClick = {
                            onPlayClickHaptic()
                            coroutineScope.launch {
                                repository.buyUpgrade(skill)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(text = value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SkillNodeCard(
    skill: SkillNode,
    playerEssence: Int,
    onUpgradeClick: () -> Unit
) {
    val maxed = skill.curLevel >= skill.maxLevel
    val canAfford = !maxed && playerEssence >= skill.currentCost

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (maxed) Color(0xFF00FF66) else if (canAfford) Color(0xFF00FFFF).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF111111)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = skill.name,
                        color = if (maxed) Color(0xFF00FF66) else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "LV ${skill.curLevel}/${skill.maxLevel}",
                        color = if (maxed) Color(0xFF00FF66) else Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = skill.description,
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                    lineHeight = 15.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Action Button showing Cost or Maxed State
            Button(
                onClick = { if (canAfford) onUpgradeClick() },
                enabled = canAfford,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canAfford) Color(0xFF00FFFF) else Color(0xFF222222),
                    disabledContainerColor = Color(0xFF151515)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                shape = RoundedCornerShape(4.dp)
            ) {
                if (maxed) {
                    Text(text = "MAXED", color = Color(0xFF00FF66), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = "UP (+${skill.currentCost})",
                        color = if (canAfford) Color.Black else Color.DarkGray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}
