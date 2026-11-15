import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Checks the Play store listings kept as files in Gradle Play Publisher's layout (`<app>/src/<set>/play/`):
 * Play's length limits, the same files in English (`en-US`) and Arabic (`ar`), nothing empty, and the copy-tone
 * words the platform never uses for itself (CLAUDE.md, wiki "Copy Tone Guide").
 */
object StoreListingRules {
    val LOCALES = setOf("en-US", "ar")
    val LIMITS = mapOf("title.txt" to 30, "short-description.txt" to 80, "full-description.txt" to 4000, "video-url.txt" to 500)
    val REQUIRED = setOf("title.txt", "short-description.txt", "full-description.txt")
    const val RELEASE_NOTES_LIMIT = 500

    /** Words the interface never uses for the platform (en, ar). Content categories don't appear in listings. */
    private val BANNED = listOf(
        "lesson", "lecture", "course", "library", "beneficial knowledge", "binge",
        "دروس", "درس", "محاضرات", "محاضرة", "مكتبة", "العلم النافع", "فيديو", "فيديوهات"
    )

    /** Problems found under one `play/` directory; empty when it is fine. */
    fun check(play: File): List<String> {
        val problems = mutableListOf<String>()
        fun rel(f: File) = f.relativeTo(play.parentFile.parentFile.parentFile).path
        fun text(f: File) = f.readText(Charsets.UTF_8).trim()

        val listings = File(play, "listings")
        val locales = listings.listFiles { f -> f.isDirectory }?.associateBy { it.name }.orEmpty()
        if (locales.keys != LOCALES) problems += "${rel(listings)}: locales ${locales.keys.sorted()} must be exactly ${LOCALES.sorted()}"
        val fileSets = locales.mapValues { (_, dir) -> dir.listFiles { f -> f.isFile && f.extension == "txt" }.orEmpty().map { it.name }.toSet() }
        fileSets.forEach { (locale, files) ->
            (REQUIRED - files).forEach { problems += "${rel(listings)}/$locale: missing $it" }
        }
        if (fileSets.values.distinct().size > 1) problems += "${rel(listings)}: en-US and ar must have the same files ($fileSets)"
        locales.values.forEach { dir ->
            dir.listFiles { f -> f.isFile && f.extension == "txt" }.orEmpty().forEach { f ->
                val t = text(f)
                val limit = LIMITS[f.name]
                when {
                    limit == null -> problems += "${rel(f)}: not a Play listing file (${LIMITS.keys.sorted()})"
                    t.isEmpty() -> problems += "${rel(f)}: empty"
                    t.codePointCount(0, t.length) > limit -> problems += "${rel(f)}: ${t.codePointCount(0, t.length)} characters, Play allows $limit"
                }
                if (f.name == "video-url.txt" && t.isNotEmpty() && !t.startsWith("https://")) problems += "${rel(f)}: must be an https URL"
                banned(t).forEach { problems += "${rel(f)}: copy tone, avoid \"$it\"" }
            }
        }

        val notes = File(play, "release-notes")
        if (notes.isDirectory) {
            val noteLocales = notes.listFiles { f -> f.isDirectory }.orEmpty().associateBy { it.name }
            if (noteLocales.keys != LOCALES) problems += "${rel(notes)}: locales ${noteLocales.keys.sorted()} must be exactly ${LOCALES.sorted()}"
            val tracks = noteLocales.mapValues { (_, d) -> d.listFiles { f -> f.isFile }.orEmpty().map { it.name }.toSet() }
            if (tracks.values.distinct().size > 1) problems += "${rel(notes)}: en-US and ar must have notes for the same tracks ($tracks)"
            noteLocales.values.flatMap { it.listFiles { f -> f.isFile }.orEmpty().toList() }.forEach { f ->
                val t = text(f)
                if (t.isEmpty()) problems += "${rel(f)}: empty"
                if (t.length > RELEASE_NOTES_LIMIT) problems += "${rel(f)}: ${t.length} characters, Play allows $RELEASE_NOTES_LIMIT"
                banned(t).forEach { problems += "${rel(f)}: copy tone, avoid \"$it\"" }
            }
        }
        return problems
    }

    private fun banned(text: String): List<String> {
        val lower = text.lowercase()
        return BANNED.filter { lower.contains(it) }
    }
}

/** Always runs (a few small files): its inputs span the repo, so no up-to-date tracking. */
abstract class CheckStoreListingsTask : DefaultTask() {
    @get:Internal
    abstract val root: DirectoryProperty

    @TaskAction
    fun check() {
        val dirs = root.get().asFile.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(".") }
            .filter { it.isDirectory && it.name == "play" && it.parentFile.parentFile.name == "src" }
            .toList()
        if (dirs.isEmpty()) throw GradleException("No store listings found (expected <app>/src/main/play/)")
        val problems = dirs.flatMap { StoreListingRules.check(it) } + dataSafetySources(root.get().asFile)
        if (problems.isNotEmpty()) throw GradleException("Store listings:\n" + problems.joinToString("\n") { "  - $it" })
        logger.lifecycle("Store listings OK: ${dirs.joinToString { it.relativeTo(root.get().asFile).path }}")
    }
}

/** Every source file cited in `store/play/data-safety.csv` must still exist, so a refactor flags the answers for review. */
internal fun dataSafetySources(root: File): List<String> {
    val csv = File(root, "store/play/data-safety.csv")
    if (!csv.isFile) return listOf("store/play/data-safety.csv is missing")
    return csv.readLines().drop(1).filter { it.isNotBlank() }.flatMap { line ->
        line.substringAfterLast(',').split(';').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            .filterNot { File(root, it).exists() }
            .map { "store/play/data-safety.csv cites $it, which no longer exists: re-check that answer" }
    }
}

/** Root-project plugin: `./gradlew checkStoreListings` (run by CI's static checks). */
class StoreListingsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register("checkStoreListings", CheckStoreListingsTask::class.java) {
            group = "verification"
            description = "Checks Play store listing files: limits, en/ar parity, copy tone."
            root.set(target.layout.projectDirectory)
        }
    }
}
