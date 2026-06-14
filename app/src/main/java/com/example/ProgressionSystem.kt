package com.example

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "skills")
data class SkillNode(
    @PrimaryKey val skillId: String,
    val name: String,
    val description: String,
    val curLevel: Int,
    val maxLevel: Int,
    val baseCost: Int,
    val multiplier: Float,
    val valuePerLevel: Float
) {
    val currentCost: Int
        get() = if (curLevel >= maxLevel) -1 else (baseCost * Math.pow(multiplier.toDouble(), curLevel.toDouble())).toInt()

    val totalBonus: Float
        get() = curLevel * valuePerLevel
}

@Entity(tableName = "profile")
data class PlayerProfile(
    @PrimaryKey val id: Int = 1,
    val essence: Int = 0,
    val runsCount: Int = 0,
    val totalKills: Int = 0,
    val highestFloor: Int = 1
)

@Dao
interface ProgressionDao {
    @Query("SELECT * FROM skills")
    fun getAllSkillsFlow(): Flow<List<SkillNode>>

    @Query("SELECT * FROM skills")
    suspend fun getAllSkills(): List<SkillNode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkills(skills: List<SkillNode>)

    @Update
    suspend fun updateSkill(skill: SkillNode)

    @Query("SELECT * FROM profile WHERE id = 1 LIMIT 1")
    fun getProfileFlow(): Flow<PlayerProfile?>

    @Query("SELECT * FROM profile WHERE id = 1 LIMIT 1")
    suspend fun getProfile(): PlayerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateProfile(profile: PlayerProfile)
}

@Database(entities = [SkillNode::class, PlayerProfile::class], version = 1, exportSchema = false)
abstract class AppGameDatabase : RoomDatabase() {
    abstract fun progressionDao(): ProgressionDao

    companion object {
        @Volatile
        private var INSTANCE: AppGameDatabase? = null

        fun getDatabase(context: Context): AppGameDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppGameDatabase::class.java,
                    "shadow_rift_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ProgressionRepository(private val dao: ProgressionDao) {
    val allSkillsFlow: Flow<List<SkillNode>> = dao.getAllSkillsFlow()
    val profileFlow: Flow<PlayerProfile?> = dao.getProfileFlow()

    suspend fun initDatabaseIfNeeded() {
        withContext(Dispatchers.IO) {
            val currentSkills = dao.getAllSkills()
            if (currentSkills.isEmpty()) {
                val initialSkills = listOf(
                    SkillNode(
                        "hp_boost",
                        "Vapor Core (HP)",
                        "Increases maximum health by +15 per level.",
                        0, 5, 20, 1.6f, 15f
                    ),
                    SkillNode(
                        "speed",
                        "Phasing Sprints",
                        "Increases movement speed by +8% per level.",
                        0, 5, 30, 1.8f, 0.08f
                    ),
                    SkillNode(
                        "damage",
                        "Rift Infusion",
                        "Increases basic attack damage by +20% per level.",
                        0, 5, 25, 1.7f, 0.20f
                    ),
                    SkillNode(
                        "crit",
                        "Void Criticals",
                        "Increases critical strike chance by +5% per level.",
                        0, 5, 40, 2.0f, 0.05f
                    )
                )
                dao.insertSkills(initialSkills)
            }
            val profile = dao.getProfile()
            if (profile == null) {
                dao.updateProfile(PlayerProfile())
            }
        }
    }

    suspend fun buyUpgrade(skill: SkillNode): Boolean {
        return withContext(Dispatchers.IO) {
            val profile = dao.getProfile() ?: PlayerProfile()
            val cost = skill.currentCost
            if (cost in 0..profile.essence && skill.curLevel < skill.maxLevel) {
                // Deduct cost and save
                val updatedProfile = profile.copy(essence = profile.essence - cost)
                dao.updateProfile(updatedProfile)

                // Level up skill
                val updatedSkill = skill.copy(curLevel = skill.curLevel + 1)
                dao.updateSkill(updatedSkill)
                true
            } else {
                false
            }
        }
    }

    suspend fun awardEssenceAndSaveScore(essenceEarned: Int, floorReached: Int, killsInSession: Int) {
        withContext(Dispatchers.IO) {
            val profile = dao.getProfile() ?: PlayerProfile()
            val updatedProfile = profile.copy(
                essence = profile.essence + essenceEarned,
                runsCount = profile.runsCount + 1,
                totalKills = profile.totalKills + killsInSession,
                highestFloor = Math.max(profile.highestFloor, floorReached)
            )
            dao.updateProfile(updatedProfile)
        }
    }
}
