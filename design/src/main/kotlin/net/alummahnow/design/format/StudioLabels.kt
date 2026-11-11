package net.alummahnow.design.format

import androidx.annotation.StringRes
import net.alummahnow.design.R

/** Localised labels for api-videos category and language values. */
object StudioLabels {
    private val categories = mapOf(
        "quran_recitation" to R.string.aun_category_quran_recitation,
        "quran_tafsir" to R.string.aun_category_quran_tafsir,
        "hadith_studies" to R.string.aun_category_hadith_studies,
        "islamic_history" to R.string.aun_category_islamic_history,
        "fiqh" to R.string.aun_category_fiqh,
        "aqeedah" to R.string.aun_category_aqeedah,
        "dawah" to R.string.aun_category_dawah,
        "khutbah" to R.string.aun_category_khutbah,
        "islamic_lectures" to R.string.aun_category_islamic_lectures,
        "islamic_nasheed" to R.string.aun_category_islamic_nasheed,
        "islamic_kids" to R.string.aun_category_islamic_kids,
        "islamic_news" to R.string.aun_category_islamic_news,
        "hajj_umrah" to R.string.aun_category_hajj_umrah,
        "ramadan" to R.string.aun_category_ramadan,
        "islamic_lifestyle" to R.string.aun_category_islamic_lifestyle
    )

    private val languages = mapOf(
        "ar" to R.string.aun_language_ar,
        "en" to R.string.aun_language_en,
        "ur" to R.string.aun_language_ur,
        "fr" to R.string.aun_language_fr
    )

    @StringRes
    fun category(value: String?): Int? = categories[value]

    @StringRes
    fun language(value: String?): Int? = languages[value]
}
