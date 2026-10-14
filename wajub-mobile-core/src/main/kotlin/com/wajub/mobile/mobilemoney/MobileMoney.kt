package com.wajub.mobile.mobilemoney

import com.wajub.mobile.mapper.int
import com.wajub.mobile.mapper.obj
import com.wajub.mobile.mapper.objects
import com.wajub.mobile.mapper.str
import com.wajub.mobile.mapper.strings
import org.json.JSONObject

/** One country of the Mobile Money tables. */
data class MobileMoneyCountry(
    /** ISO-2, upper case. */
    val code: String,
    val name: String,
    /** `+237`. */
    val dialCode: String,
    /** National digits a valid number has, or null to accept 7 to 15 digits. */
    val nationalNumberLength: Int?,
    /** Côte d'Ivoire keeps its leading 0 in E.164. */
    val keepsTrunkZero: Boolean,
    val placeholder: String,
    val placeholderFormatted: String,
    val operators: List<String>,
    val prefixRules: List<PrefixRule>,
)

data class PrefixRule(val startsWith: List<String>, val operator: String)

/** A badge drawn when an operator has no logo, or its logo does not load. */
data class BrandBadge(
    val label: String,
    /** Text drawn in the badge. */
    val mark: String,
    /** `#RRGGBB` (or `#RGB`). */
    val color: String,
    val background: String,
)

/** The shared Mobile Money tables (`mobile-money.json`, synced from the spec). */
class MobileMoneyData internal constructor(
    val schemaVersion: Int,
    val countries: Map<String, MobileMoneyCountry>,
    private val brands: Map<String, BrandBadge?>,
    /** Wallets held whatever the operator: any number may pay with them. */
    val operatorIndependentWallets: Set<String>,
    private val fallbackColor: String,
    private val fallbackBackground: String,
    private val looseMin: Int,
    private val looseMax: Int,
) {
    fun country(code: String?): MobileMoneyCountry? = code?.let { countries[it.uppercase()] }

    /** The brand label for [operator] (`MTN MoMo`), when the tables have one. */
    fun brandLabel(operator: String): String? = brands[operator]?.label

    /** The badge for [operator]; [fallbackName] names it when the tables have no brand. */
    fun badge(operator: String, fallbackName: String): BrandBadge {
        val brand = brands[operator]
        if (brand != null) return brand
        return BrandBadge(fallbackName, markOf(fallbackName), fallbackColor, fallbackBackground)
    }

    internal fun looseValid(digits: Int): Boolean = digits in looseMin..looseMax

    companion object {
        /** The only schema this SDK reads (spec MM-09). */
        const val SUPPORTED_SCHEMA_VERSION: Int = 1

        fun parse(text: String): MobileMoneyData {
            val root = JSONObject(text)
            val version = root.int("schema_version")
            require(version == SUPPORTED_SCHEMA_VERSION) {
                "mobile-money.json schema_version $version is not supported (expected $SUPPORTED_SCHEMA_VERSION)"
            }
            val countries = linkedMapOf<String, MobileMoneyCountry>()
            root.obj("countries")?.let { all ->
                all.keys().forEach { code ->
                    val c = all.getJSONObject(code)
                    countries[code.uppercase()] = MobileMoneyCountry(
                        code = code.uppercase(),
                        name = c.str("name") ?: code,
                        dialCode = c.str("dial_code").orEmpty(),
                        nationalNumberLength = c.int("national_number_length"),
                        keepsTrunkZero = c.str("e164_trunk_zero") == "keep",
                        placeholder = c.str("placeholder").orEmpty(),
                        placeholderFormatted = c.str("placeholder_formatted").orEmpty(),
                        operators = c.strings("operators"),
                        prefixRules = c.objects("prefix_rules").mapNotNull { rule ->
                            rule.str("operator")?.let { PrefixRule(rule.strings("starts_with"), it) }
                        },
                    )
                }
            }
            val fallback = root.obj("fallback_brand")
            val fallbackColor = fallback?.str("color") ?: "#ffffff"
            val fallbackBackground = fallback?.str("background") ?: "#525252"
            val brands = linkedMapOf<String, BrandBadge?>()
            root.obj("operators")?.let { all ->
                all.keys().forEach { id ->
                    val op = all.getJSONObject(id)
                    brands[id] = op.obj("brand")?.let { b ->
                        val label = b.str("label") ?: op.str("name") ?: id
                        BrandBadge(
                            label = label,
                            mark = b.str("mark") ?: markOf(label),
                            color = b.str("color") ?: fallbackColor,
                            background = b.str("background") ?: fallbackBackground,
                        )
                    }
                }
            }
            val loose = root.obj("loose_length")
            return MobileMoneyData(
                schemaVersion = version,
                countries = countries,
                brands = brands,
                operatorIndependentWallets = root.strings("operator_independent_wallets").toSet(),
                fallbackColor = fallbackColor,
                fallbackBackground = fallbackBackground,
                looseMin = loose?.int("min") ?: 7,
                looseMax = loose?.int("max") ?: 15,
            )
        }

        // algorithms.brand_fallback: the first 8 alphanumerics, upper-cased.
        private fun markOf(label: String): String = label.filter { it.isLetterOrDigit() }.take(8).uppercase()
    }
}

/**
 * The checkout's Mobile Money rules (spec §6): number clean-up, validity,
 * operator detection, E.164 and the as-you-type format — the algorithms of
 * `mobile-money.json`, applied to its tables.
 */
object MobileMoney {
    private const val RESOURCE = "/com/wajub/mobile/mobile-money.json"

    @Volatile
    private var loaded: MobileMoneyData? = null

    /** The tables, read once from the SDK's copy of `mobile-money.json`. */
    fun data(): MobileMoneyData =
        loaded ?: synchronized(this) {
            loaded ?: run {
                val stream = MobileMoney::class.java.getResourceAsStream(RESOURCE)
                    ?: error("$RESOURCE is missing from the SDK")
                MobileMoneyData.parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).also { loaded = it }
            }
        }

    /** Digits only: every character but 0-9 is dropped (a `+` too). */
    fun sanitize(input: String): String = input.filter { it in '0'..'9' }

    /** The number without its dial code and without leading zeros. */
    fun nationalDigits(input: String, country: MobileMoneyCountry): String {
        var digits = sanitize(input)
        val dial = sanitize(country.dialCode)
        if (dial.isNotEmpty() && digits.startsWith(dial)) digits = digits.removePrefix(dial)
        return digits.trimStart('0')
    }

    fun isValid(input: String, country: MobileMoneyCountry): Boolean {
        val length = country.nationalNumberLength
        return if (length != null) {
            nationalDigits(input, country).length == length
        } else {
            data().looseValid(sanitize(input).length)
        }
    }

    /** The operator the number's prefix names, or null. */
    fun detectOperator(input: String, country: MobileMoneyCountry): String? {
        val digits = nationalDigits(input, country)
        if (digits.isEmpty()) return null
        val independent = data().operatorIndependentWallets
        return country.prefixRules.firstOrNull { rule -> rule.startsWith.any { digits.startsWith(it) } }
            ?.operator
            ?.takeIf { it !in independent }
    }

    /** `+237677123456`; Côte d'Ivoire keeps its leading 0 (`+2250701020304`). */
    fun e164(input: String, country: MobileMoneyCountry): String =
        country.dialCode + (if (country.keepsTrunkZero) "0" else "") + nationalDigits(input, country)

    /** Digits grouped by two: `671234567` → `67 12 34 56 7` (at most 15 digits). */
    fun formatAsYouType(input: String): String = sanitize(input).take(15).chunked(2).joinToString(" ")
}
